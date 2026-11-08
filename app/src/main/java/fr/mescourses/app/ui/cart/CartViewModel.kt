package fr.mescourses.app.ui.cart

import fr.mescourses.core.carrefour.Product
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.carrefour.SessionState
import fr.mescourses.app.llm.ChatEvent
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, RECHERCHE, VALIDATION, ENVOI, TERMINE }

data class CartState(
    val phase: Phase = Phase.IDLE,
    val source: String = "Liste de courses",
    val lines: List<CartLine> = emptyList(),
    val error: String? = null,
    val cart: Cart? = null,
    val slots: List<TimeSlot>? = null,
    val loadingInfo: Boolean = false,
    /** Articles du panier que le drive ne pourra pas servir, avec des remplaçants proposés. */
    val toReplace: List<Replacement> = emptyList(),
) {
    /** Carrefour n'a pas encore donné l'identifiant du drive (panier vide et aucune offre trouvée). */
    val noDrive: Boolean get() = lines.any { it.status == LineStatus.ERREUR && it.message == NO_DRIVE_MESSAGE }
    val added: Int get() = lines.count { it.status == LineStatus.AJOUTE }

    companion object {
        val NO_DRIVE_MESSAGE: String? = fr.mescourses.core.carrefour.CarrefourException.NoDrive().message
    }
}

/** Un article du panier indisponible au drive et ses remplaçants disponibles. */
data class Replacement(val ean: String, val name: String, val quantity: Int, val alternatives: List<Product>, val done: Boolean = false)

/** Fiche produit affichée dans une feuille (photo, description, composition). */
sealed interface ProductSheet {
    data class Loading(val product: Product) : ProductSheet
    data class Loaded(val product: Product, val detail: fr.mescourses.core.carrefour.CarrefourParsers.ProductDetail?) : ProductSheet
}

class CartViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CartState())
    val state: StateFlow<CartState> = _state
    val session = c.session.state
    val settings = c.settings.state
    private var job: Job? = null

    init {
        // À l'ouverture de l'onglet : session, puis panier et articles à remplacer.
        viewModelScope.launch { if (c.session.ensureSession()) loadInfo() }
        // Envoi demandé depuis une recette ou la liste de courses.
        viewModelScope.launch {
            c.pendingCart.collect { pending ->
                if (pending != null) {
                    c.pendingCart.value = null
                    prepare(pending.items, pending.label)
                }
            }
        }
    }

    private fun fail(e: Throwable) {
        c.session.onError(e)
        _state.update { it.copy(error = e.message) }
    }

    /** Prépare le panier : un produit par article (mémoire des choix, sinon recherche). */
    fun prepare(items: List<ShoppingItem>? = null, label: String = "Liste de courses") {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { CartState(phase = Phase.RECHERCHE, source = label) }
            if (!c.session.ensureSession()) {
                _state.update { it.copy(phase = Phase.IDLE, error = "Connecte-toi à Carrefour pour préparer le panier.") }
                return@launch
            }
            val list = ShoppingListMerger.merge(items ?: c.shopping.all())
            if (list.isEmpty()) {
                _state.update { it.copy(phase = Phase.IDLE, error = "Rien à envoyer : la liste est vide.") }
                return@launch
            }
            _state.update { it.copy(lines = list.map { item -> CartLine(item) }) }
            var i = 0
            CartPlanner.propose(c.carrefour, list, c.settings.current.preferOrganic, c.productMemory).collect { line ->
                val index = i++
                _state.update { s -> s.copy(lines = s.lines.toMutableList().also { it[index] = line }) }
            }
            _state.update { it.copy(phase = Phase.VALIDATION) }
        }
    }

    fun updateLine(index: Int, transform: (CartLine) -> CartLine) =
        _state.update { s -> s.copy(lines = s.lines.toMutableList().also { it[index] = transform(it[index]) }) }

    /** Pour un produit repris de la mémoire : chercher d'autres choix. */
    fun moreChoices(index: Int) = viewModelScope.launch {
        val line = _state.value.lines.getOrNull(index) ?: return@launch
        val found = CartPlanner.searchLine(c.carrefour, line.item, c.settings.current.preferOrganic)
        val merged = (line.candidates + found.candidates).distinctBy { it.ean }
        updateLine(index) { it.copy(candidates = merged, remembered = false, status = if (merged.isEmpty()) found.status else LineStatus.PROPOSE) }
    }

    /** Confirmation groupée : envoie les lignes cochées. */
    fun send() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.ENVOI, error = null) }
            try {
                CartPlanner.fill(c.carrefour, _state.value.lines, c.productMemory).collect { (index, line) -> updateLine(index) { line } }
                val failures = _state.value.lines.count { it.status == LineStatus.ERREUR }
                _state.update { it.copy(phase = Phase.TERMINE, error = if (failures > 0) "$failures article(s) en erreur" else null) }
                loadInfo()
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(phase = Phase.VALIDATION) }
            }
        }
    }

    /** Panier actuel et prochains créneaux (lecture seule). */
    fun loadInfo() = viewModelScope.launch {
        _state.update { it.copy(loadingInfo = true) }
        try {
            val cart = c.carrefour.cart()
            val slots = runCatching { c.carrefour.timeslots(5) }.getOrNull()
            _state.update { it.copy(cart = cart, slots = slots) }
            // Le drive signale-t-il des articles à remplacer ?
            checkUnavailable()
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(loadingInfo = false) }
        }
    }

    fun reset() = _state.update { CartState() }

    // ---------- Fiche produit ----------

    val productSheet = MutableStateFlow<ProductSheet?>(null)

    fun openProduct(p: Product) {
        productSheet.value = ProductSheet.Loading(p)
        viewModelScope.launch {
            val detail = runCatching { c.carrefour.productDetail(p.ean) }.getOrNull()
            // Garde les infos de la recherche (prix au drive) et complète avec la fiche.
            if (productSheet.value is ProductSheet.Loading) productSheet.value = ProductSheet.Loaded(p, detail)
        }
    }

    fun closeProduct() { productSheet.value = null }

    // ---------- Indisponibles du panier ----------

    /** Demande au drive les articles du panier qu'il ne pourra pas servir, et cherche des remplaçants. */
    fun checkUnavailable() = viewModelScope.launch {
        val eans = runCatching { c.carrefour.unavailableInCart() }.getOrDefault(emptyList())
        if (eans.isEmpty()) { _state.update { it.copy(toReplace = emptyList()) }; return@launch }
        val cart = _state.value.cart ?: runCatching { c.carrefour.cart() }.getOrNull()
        val replacements = eans.map { ean ->
            val inCart = cart?.lines?.firstOrNull { it.ean == ean }
            val sent = _state.value.lines.firstOrNull { it.product?.ean == ean }
            val name = inCart?.name ?: sent?.product?.name ?: ean
            val query = sent?.item?.let { CartPlanner.searchQuery(it, c.settings.current.preferOrganic) } ?: name
            val alternatives = runCatching { c.carrefour.search(query, 6) }.getOrDefault(emptyList())
                .filter { it.ean != ean && it.available }.take(3)
            Replacement(ean, name, inCart?.quantity ?: sent?.quantity ?: 1, alternatives)
        }
        _state.update { it.copy(toReplace = replacements) }
    }

    /** Remplace un article indisponible : quantité 0 pour l'ancien, ajout du nouveau. */
    fun replace(r: Replacement, product: Product) = viewModelScope.launch {
        try {
            c.carrefour.setQuantity(r.ean, 0)
            c.carrefour.addToCart(product, r.quantity)
            _state.update { s -> s.copy(toReplace = s.toReplace.map { if (it.ean == r.ean) it.copy(done = true) else it }) }
            loadInfo()
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Coche dans la liste de courses les articles envoyés au panier. */
    fun markSentAsBought() = viewModelScope.launch {
        val sentKeys = _state.value.lines.filter { it.status == LineStatus.AJOUTE }.map { ShoppingListMerger.key(it.item.name) }.toSet()
        c.shopping.all().filter { !it.checked && ShoppingListMerger.key(it.name) in sentKeys }
            .forEach { c.shopping.update(it.copy(checked = true)) }
        _state.update { CartState() }
    }

    fun isLoggedIn() = session.value == SessionState.CONNECTE
}

// ---------- Mode « Courses avec l'assistant » ----------

data class ShoppingChatLine(val fromUser: Boolean, val text: String, val tool: Boolean = false, val ok: Boolean = true)

class ShoppingChatViewModel(private val c: AppContainer) : ViewModel() {
    val model: StateFlow<ModelStatus> = c.model.status
    val lines = MutableStateFlow<List<ShoppingChatLine>>(emptyList())
    val busy = MutableStateFlow(false)
    private var job: Job? = null

    private companion object {
        const val OWNER = "courses"
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        job = viewModelScope.launch {
            busy.value = true
            lines.update { it + ShoppingChatLine(true, t) }
            try {
                if (!c.model.owns(OWNER)) c.model.startConversation(OWNER, c.shoppingSystemPrompt(), c.shoppingTools())
                c.model.use {
                    c.model.send(t).collect { ev ->
                        when (ev) {
                            is ChatEvent.Token -> lines.update { l ->
                                val last = l.lastOrNull()
                                if (last != null && !last.fromUser && !last.tool) l.dropLast(1) + last.copy(text = last.text + ev.text)
                                else l + ShoppingChatLine(false, ev.text)
                            }
                            is ChatEvent.ToolCall -> lines.update { it + ShoppingChatLine(false, "🔎 ${label(ev.name)}…", tool = true) }
                            is ChatEvent.ToolResult -> lines.update { l ->
                                val i = l.indexOfLast { it.tool }
                                if (i < 0) l else l.toMutableList().also { m ->
                                    m[i] = ShoppingChatLine(false, (if (ev.ok) "✅ " else "⚠️ ") + label(ev.name), tool = true, ok = ev.ok)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                c.session.onError(e)
                lines.update { it + ShoppingChatLine(false, "⚠️ ${e.message}", tool = true, ok = false) }
            } finally {
                busy.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        busy.value = false
    }

    private fun label(tool: String) = when (tool) {
        "chercher_produit" -> "Recherche chez Carrefour"
        "details_produit" -> "Détails du produit"
        "voir_panier" -> "Lecture du panier"
        "ajouter_au_panier" -> "Ajout au panier"
        "modifier_quantite" -> "Modification du panier"
        "creneaux_drive" -> "Créneaux du drive"
        else -> tool
    }
}

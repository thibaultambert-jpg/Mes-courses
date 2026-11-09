package fr.mescourses.app.ui.cart

import fr.mescourses.core.carrefour.Product
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.PendingCart
import fr.mescourses.app.retail.RetailerSite
import fr.mescourses.app.retail.SessionState
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.retail.Retailer
import fr.mescourses.app.llm.ChatEvent
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import fr.mescourses.core.carrefour.CarrefourClient
import kotlinx.coroutines.flow.stateIn
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
    /** Enseigne chez qui les produits des lignes ont été cherchés. */
    val retailer: Retailer = Retailer.CARREFOUR,
    /** Articles du panier que le drive ne pourra pas servir, avec des remplaçants proposés. */
    val toReplace: List<Replacement> = emptyList(),
) {
    /** L'enseigne n'a pas encore donné l'identifiant du drive (Carrefour : panier vide et aucune offre trouvée). */
    val noDrive: Boolean get() = lines.any { it.status == LineStatus.ERREUR && it.message == CarrefourException.NoDrive(retailer.label).message }
    val added: Int get() = lines.count { it.status == LineStatus.AJOUTE }
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
    /** Enseigne active (la préférée par défaut). */
    val retailer: StateFlow<Retailer> = c.activeRetailer
    @OptIn(ExperimentalCoroutinesApi::class)
    val session: StateFlow<SessionState> = c.activeRetailer.flatMapLatest { c.session(it).state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.INCONNU)
    val settings = c.settings.state
    private var job: Job? = null

    fun site(r: Retailer): RetailerSite = c.bridge(r).site

    init {
        // À l’ouverture de l onglet, puis à chaque changement d’enseigne : session, puis panier et articles à remplacer.
        viewModelScope.launch { c.activeRetailer.collectLatest { if (c.session(it).ensureSession()) loadInfo() } }
        // Panier choisi dans la comparaison des prix : repris tel quel, prêt à vérifier.
        viewModelScope.launch {
            c.pendingQuote.collect { q ->
                if (q != null) {
                    c.pendingQuote.value = null
                    job?.cancel()
                    c.activeRetailer.value = q.retailer
                    _state.value = CartState(phase = Phase.VALIDATION, source = q.label, lines = q.lines, retailer = q.retailer)
                }
            }
        }
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
        c.session(_state.value.retailer).onError(e)
        _state.update { it.copy(error = e.message) }
    }

    /** Prépare le panier : un produit par article (mémoire des choix, sinon recherche). */
    fun prepare(items: List<ShoppingItem>? = null, label: String = "Liste de courses") {
        job?.cancel()
        job = viewModelScope.launch {
            val r = c.activeRetailer.value
            _state.update { CartState(phase = Phase.RECHERCHE, source = label, retailer = r) }
            val session = c.session(r)
            if (!session.ensureSession()) {
                _state.update { it.copy(phase = Phase.IDLE, error = session.problem()) }
                return@launch
            }
            val list = ShoppingListMerger.merge(items ?: c.shopping.all())
            if (list.isEmpty()) {
                _state.update { it.copy(phase = Phase.IDLE, error = "Rien à envoyer : la liste est vide.") }
                return@launch
            }
            _state.update { it.copy(lines = list.map { item -> CartLine(item) }) }
            var i = 0
            CartPlanner.propose(c.backend(r), list, c.settings.current.preferOrganic, c.productMemory(r), queries = c.settings.current.queryMap()).collect { line ->
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
        val found = CartPlanner.searchLine(
            c.backend(_state.value.retailer), line.item, c.settings.current.preferOrganic,
            CartPlanner.queryFor(line.item, c.settings.current.queryMap()),
        )
        val merged = (line.candidates + found.candidates).distinctBy { it.ean }
        updateLine(index) {
            it.copy(
                candidates = merged, remembered = false, query = found.query,
                relevantCount = (line.candidates.size + found.relevantCount).coerceAtMost(merged.size),
                status = if (merged.isEmpty()) found.status else LineStatus.PROPOSE,
            )
        }
    }

    /**
     * Relance la recherche d'une seule ligne avec les mots choisis par l'utilisateur ; la recherche est mémorisée
     * pour cet ingrédient si [remember] (prochains paniers et comparaison des prix).
     */
    fun searchAgain(index: Int, query: String, remember: Boolean = true) = viewModelScope.launch {
        val line = _state.value.lines.getOrNull(index) ?: return@launch
        val q = query.trim().ifEmpty { return@launch }
        updateLine(index) { it.copy(status = LineStatus.A_CHERCHER, query = q) }
        val found = CartPlanner.searchLine(c.backend(_state.value.retailer), line.item, c.settings.current.preferOrganic, q)
        updateLine(index) { found.copy(quantity = line.quantity) }
        if (remember) {
            val key = fr.mescourses.core.shopping.ShoppingListMerger.key(line.item.name)
            val auto = CartPlanner.searchQuery(line.item)
            c.settings.update { it.withQuery(key, if (q.equals(auto, ignoreCase = true)) "" else q) }
        }
    }

    /** Recherche plus large (sans le dernier mot). */
    fun widen(index: Int) {
        val line = _state.value.lines.getOrNull(index) ?: return
        val q = line.query ?: CartPlanner.searchQuery(line.item)
        CartPlanner.widen(q)?.let { searchAgain(index, it) }
    }

    /** Confirmation groupée : envoie les lignes cochées. */
    fun send() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.ENVOI, error = null) }
            try {
                val r = _state.value.retailer
                CartPlanner.fill(c.backend(r), _state.value.lines, c.productMemory(r)).collect { (index, line) -> updateLine(index) { line } }
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
        val r = c.activeRetailer.value
        _state.update { it.copy(loadingInfo = true) }
        try {
            val cart = c.backend(r).cart()
            val slots = runCatching { c.backend(r).timeslots(5) }.getOrNull()
            _state.update { it.copy(cart = cart, slots = slots) }
            // Le drive signale-t-il des articles à remplacer ?
            checkUnavailable()
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(loadingInfo = false) }
        }
    }

    fun reset() = _state.update { CartState(retailer = c.activeRetailer.value) }

    /**
     * Change l'enseigne du panier (sans toucher à l'enseigne préférée). Les produits proposés appartiennent
     * à l'ancienne enseigne : la recherche est relancée sur les mêmes articles.
     */
    fun switchTo(r: Retailer) {
        if (r == c.activeRetailer.value) return
        c.activeRetailer.value = r
        val s = _state.value
        when (s.phase) {
            Phase.VALIDATION, Phase.RECHERCHE -> if (s.lines.isNotEmpty()) prepare(s.lines.map { it.item }, s.source) else reset()
            else -> { job?.cancel(); _state.value = CartState(retailer = r) }
        }
    }

    /** Articles à comparer : ceux du panier en cours, sinon la liste de courses. */
    fun prepareCompare() {
        val s = _state.value
        c.pendingCompare.value = if (s.lines.isNotEmpty()) PendingCart(s.source, s.lines.map { it.item }) else null
    }

    // ---------- Fiche produit ----------

    val productSheet = MutableStateFlow<ProductSheet?>(null)

    fun openProduct(p: Product) {
        productSheet.value = ProductSheet.Loading(p)
        viewModelScope.launch {
            // Fiche détaillée (composition, allergènes) : Carrefour seulement ; ailleurs, les infos de la recherche.
            val detail = runCatching { (c.backend() as? CarrefourClient)?.productDetail(p.ean) }.getOrNull()
            // Garde les infos de la recherche (prix au drive) et complète avec la fiche.
            if (productSheet.value is ProductSheet.Loading) productSheet.value = ProductSheet.Loaded(p, detail)
        }
    }

    fun closeProduct() { productSheet.value = null }

    // ---------- Indisponibles du panier ----------

    /** Demande au drive les articles du panier qu'il ne pourra pas servir, et cherche des remplaçants. */
    fun checkUnavailable() = viewModelScope.launch {
        val eans = runCatching { c.backend().unavailableInCart() }.getOrDefault(emptyList())
        if (eans.isEmpty()) { _state.update { it.copy(toReplace = emptyList()) }; return@launch }
        val cart = _state.value.cart ?: runCatching { c.backend().cart() }.getOrNull()
        val replacements = eans.map { ean ->
            val inCart = cart?.lines?.firstOrNull { it.ean == ean }
            val sent = _state.value.lines.firstOrNull { it.product?.ean == ean }
            val name = inCart?.name ?: sent?.product?.name ?: ean
            val query = sent?.item?.let { CartPlanner.queryFor(it, c.settings.current.queryMap()) } ?: name
            val alternatives = runCatching { c.backend().search(query, 6) }.getOrDefault(emptyList())
                .filter { it.ean != ean && it.available }.take(3)
            Replacement(ean, name, inCart?.quantity ?: sent?.quantity ?: 1, alternatives)
        }
        _state.update { it.copy(toReplace = replacements) }
    }

    /** Remplace un article indisponible : quantité 0 pour l'ancien, ajout du nouveau. */
    fun replace(r: Replacement, product: Product) = viewModelScope.launch {
        try {
            val backend = c.backend()
            backend.setQuantity(r.ean, 0)
            backend.addToCart(product, r.quantity)
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
        _state.update { CartState(retailer = c.activeRetailer.value) }
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

    /** Une conversation par enseigne : les outils de Gemma sont liés au drive de l'enseigne active. */
    private val owner: String get() = "courses-" + c.activeRetailer.value.name

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        job = viewModelScope.launch {
            busy.value = true
            lines.update { it + ShoppingChatLine(true, t) }
            try {
                val id = owner
                if (!c.model.owns(id)) c.model.startConversation(id, c.shoppingSystemPrompt(), c.shoppingTools())
                try {
                    chat(t)
                } catch (e: Exception) {
                    // Mémoire de l'assistant pleine : conversation neuve, un seul nouvel essai.
                    if (e is kotlinx.coroutines.CancellationException || !fr.mescourses.app.llm.ModelController.isContextFull(e)) throw e
                    lines.update { it + ShoppingChatLine(false, "↻ Mémoire de l'assistant pleine : nouvelle conversation.", tool = true, ok = true) }
                    c.model.startConversation(id, c.shoppingSystemPrompt(), c.shoppingTools())
                    val last = lines.value.lastOrNull { !it.fromUser && !it.tool }?.text?.trim()?.takeLast(600)
                    chat(last?.let { "Rappel de ta réponse précédente : « $it »\n$t" } ?: t)
                }
            } catch (e: Exception) {
                c.session().onError(e)
                lines.update { it + ShoppingChatLine(false, "⚠️ ${e.message}", tool = true, ok = false) }
            } finally {
                busy.value = false
            }
        }
    }

    /** Envoie un message à l'assistant et affiche sa réponse et ses recherches au fil de l'eau. */
    private suspend fun chat(t: String) {
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
    }

    fun stop() {
        job?.cancel()
        busy.value = false
    }

    private fun label(tool: String) = when (tool) {
        "chercher_produit" -> "Recherche chez ${c.activeRetailer.value.label}"
        "details_produit" -> "Détails du produit"
        "voir_panier" -> "Lecture du panier"
        "ajouter_au_panier" -> "Ajout au panier"
        "modifier_quantite" -> "Modification du panier"
        "creneaux_drive" -> "Créneaux du drive"
        else -> tool
    }
}

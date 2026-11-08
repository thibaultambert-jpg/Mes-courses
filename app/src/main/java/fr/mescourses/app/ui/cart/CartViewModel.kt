package fr.mescourses.app.ui.cart

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
) {
    /** L'enseigne n'a pas encore donné l'identifiant du drive (Carrefour : panier vide et aucune offre trouvée). */
    val noDrive: Boolean get() = lines.any { it.status == LineStatus.ERREUR && it.message == CarrefourException.NoDrive(retailer.label).message }
    val added: Int get() = lines.count { it.status == LineStatus.AJOUTE }
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
        // Vérifie la session de l'enseigne active, et de nouveau à chaque changement d'enseigne.
        viewModelScope.launch { c.activeRetailer.collect { c.session(it).ensureSession() } }
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
            CartPlanner.propose(c.backend(r), list, c.settings.current.preferOrganic, c.productMemory(r)).collect { line ->
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
        val found = CartPlanner.searchLine(c.backend(_state.value.retailer), line.item, c.settings.current.preferOrganic)
        val merged = (line.candidates + found.candidates).distinctBy { it.ean }
        updateLine(index) { it.copy(candidates = merged, remembered = false, status = if (merged.isEmpty()) found.status else LineStatus.PROPOSE) }
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
                c.session().onError(e)
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
        "chercher_produit" -> "Recherche chez ${c.activeRetailer.value.label}"
        "details_produit" -> "Détails du produit"
        "voir_panier" -> "Lecture du panier"
        "ajouter_au_panier" -> "Ajout au panier"
        "modifier_quantite" -> "Modification du panier"
        "creneaux_drive" -> "Créneaux du drive"
        else -> tool
    }
}

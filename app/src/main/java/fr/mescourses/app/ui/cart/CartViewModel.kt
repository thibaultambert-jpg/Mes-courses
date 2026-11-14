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
import fr.mescourses.app.llm.ModelController
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.agent.str
import fr.mescourses.core.carrefour.compact
import fr.mescourses.core.llm.CarryOver
import fr.mescourses.core.llm.ConversationPolicy
import fr.mescourses.core.llm.Rollover
import fr.mescourses.core.llm.TokenEstimate
import fr.mescourses.core.llm.Transcript
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
        val cart = _state.value.cart ?: runCatching { c.backend().cart() }.getOrNull()
        // Panier vidé (sur le site ou ailleurs) : la simulation du drive peut garder d'anciens articles, on les ignore.
        val inCartEans = cart?.lines.orEmpty().filter { it.quantity > 0 }.map { it.ean }.toSet()
        val eans = if (inCartEans.isEmpty()) emptyList()
        else runCatching { c.backend().unavailableInCart() }.getOrDefault(emptyList()).filter { it in inCartEans }
        if (eans.isEmpty()) { _state.update { it.copy(toReplace = emptyList()) }; return@launch }
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
    private var warmJob: Job? = null

    /** Dernières recherches et dernière réponse par conversation (reportés lors d'une rotation de contexte). */
    private val seenByOwner = mutableMapOf<String, LinkedHashSet<String>>()
    private val lastAnswerByOwner = mutableMapOf<String, String>()

    /** Une conversation par enseigne : les outils de Gemma sont liés au drive de l'enseigne active. */
    private val owner: String get() = "courses-" + c.activeRetailer.value.name

    init {
        warmUp()
    }

    /** Pré-chauffe le prompt système et les outils à l'entrée de l'écran pendant que l'utilisateur tape. */
    private fun warmUp() {
        val id = owner
        if (!c.model.status.value.available || c.model.hasConversation(id)) return
        warmJob = viewModelScope.launch {
            try {
                c.model.use {
                    if (!c.model.hasConversation(id)) {
                        c.model.startConversation(
                            id,
                            c.shoppingSystemPrompt(),
                            c.shoppingTools(),
                            prefill = true,
                            maxOutputTokens = MAX_OUTPUT_TOKENS,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pré-chauffage facultatif : send() démarrera la conversation et remontera l'erreur si besoin.
            }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        job = viewModelScope.launch {
            busy.value = true
            c.model.setChatForeground(true)
            lines.update { it + ShoppingChatLine(true, t) }
            try {
                warmJob?.join()
                val id = owner
                val system = c.shoppingSystemPrompt()
                val tools = c.shoppingTools()
                if (!c.model.hasConversation(id)) {
                    c.model.startConversation(id, system, tools, prefill = false, maxOutputTokens = MAX_OUTPUT_TOKENS)
                } else {
                    check(c.model.ensureLoaded()) { "Assistant indisponible" }
                    val policy = ConversationPolicy(c.model.budget(ModelController.Kind.COURSES))
                    val transcript = c.model.transcript(id) ?: Transcript()
                    val tokenCount = if (c.model.owns(id)) c.model.tokenCount() else null
                    val decision = policy.decide(
                        tokenCount = tokenCount,
                        transcript = transcript,
                        systemTokens = systemTokens(system, tools),
                        nextMessage = t,
                        expectedToolTokens = EXPECTED_TOOL_TOKENS,
                    ) { emptyList() }
                    if (decision is Rollover.Recreate) {
                        lines.update { it + ShoppingChatLine(false, "↻ Nouvelle conversation (mémoire)", tool = true, ok = true) }
                        val initial = CarryOver.courses(cartSummary(), seenFor(id), lastAnswerByOwner[id])
                        c.model.startConversation(id, system, tools, initial = initial, prefill = false, maxOutputTokens = MAX_OUTPUT_TOKENS)
                    }
                }
                try {
                    chat(id, t)
                } catch (e: Exception) {
                    // Mémoire de l'assistant pleine : conversation neuve, un seul nouvel essai.
                    if (e is CancellationException || !ModelController.isContextFull(e)) throw e
                    lines.update { it + ShoppingChatLine(false, "↻ Mémoire de l'assistant pleine : nouvelle conversation.", tool = true, ok = true) }
                    val initial = CarryOver.courses(cartSummary(), seenFor(id), lastAnswerByOwner[id])
                    c.model.startConversation(id, system, tools, initial = initial, prefill = false, maxOutputTokens = MAX_OUTPUT_TOKENS)
                    chat(id, t)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                c.session().onError(e)
                lines.update { it + ShoppingChatLine(false, "⚠️ ${e.message}", tool = true, ok = false) }
            } finally {
                busy.value = false
                c.model.setChatForeground(false)
            }
        }
    }

    /** Envoie un message à l'assistant et affiche sa réponse et ses recherches au fil de l'eau. */
    private suspend fun chat(id: String, t: String) {
        val answer = StringBuilder()
        c.model.use {
            c.model.send(id, t).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> {
                        answer.append(ev.text)
                        lines.update { l ->
                            val last = l.lastOrNull()
                            if (last != null && !last.fromUser && !last.tool) l.dropLast(1) + last.copy(text = last.text + ev.text)
                            else l + ShoppingChatLine(false, ev.text)
                        }
                    }
                    is ChatEvent.ToolCall -> {
                        recordToolCall(id, ev.name, ev.arguments)
                        lines.update { it + ShoppingChatLine(false, "🔎 ${label(ev.name)}…", tool = true) }
                    }
                    is ChatEvent.ToolResult -> lines.update { l ->
                        val i = l.indexOfLast { it.tool }
                        if (i < 0) l else l.toMutableList().also { m ->
                            m[i] = ShoppingChatLine(false, (if (ev.ok) "✅ " else "⚠️ ") + label(ev.name), tool = true, ok = ev.ok)
                        }
                    }
                }
            }
        }
        answer.toString().trim().takeIf { it.isNotEmpty() }?.let { lastAnswerByOwner[id] = it }
    }

    private fun recordToolCall(id: String, name: String, arguments: String) {
        if (name != "chercher_produit") return
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return
        val q = (args.str("requete") ?: args.str("query"))?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val set = seenByOwner.getOrPut(id) { LinkedHashSet() }
        set.remove(q)
        set.add(q)
    }

    private fun seenFor(id: String): List<String> = seenByOwner[id]?.toList().orEmpty().takeLast(MAX_SEEN)

    private fun systemTokens(system: String, tools: List<AgentTool>): Int =
        TokenEstimate.text(system) + TokenEstimate.tools(tools.map { it.openApiJson() })

    private suspend fun cartSummary(): String? = runCatching {
        val cart = c.backend().cart()
        val active = cart.lines.filter { it.quantity > 0 }
        if (active.isEmpty()) return@runCatching Cart(emptyList(), cart.total, cart.context).compact().toString()
        var count = active.size
        var json = Cart(active, cart.total, cart.context).compact().toString()
        while (json.length > MAX_CART_SUMMARY_CHARS && count > 1) {
            count--
            json = Cart(active.take(count), cart.total, cart.context).compact().toString()
        }
        val omitted = active.size - count
        val bounded = if (json.length <= MAX_CART_SUMMARY_CHARS) json else json.take(MAX_CART_SUMMARY_CHARS - 1) + "…"
        if (omitted > 0) "$bounded (+$omitted autre(s))" else bounded
    }.getOrNull()

    fun stop() {
        job?.cancel()
        busy.value = false
        c.model.setChatForeground(false)
    }

    override fun onCleared() {
        c.model.setChatForeground(false)
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

    private companion object {
        const val EXPECTED_TOOL_TOKENS = 700
        const val MAX_OUTPUT_TOKENS = 400
        const val MAX_CART_SUMMARY_CHARS = 450
        const val MAX_SEEN = 12
    }
}

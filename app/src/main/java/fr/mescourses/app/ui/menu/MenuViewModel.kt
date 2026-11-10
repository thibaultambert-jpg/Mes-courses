package fr.mescourses.app.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.llm.ChatEvent
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.work.Validation
import fr.mescourses.core.menu.CandidateFinder
import fr.mescourses.core.menu.Chosen
import fr.mescourses.core.menu.CookingTime
import fr.mescourses.core.menu.DishKind
import fr.mescourses.core.menu.IngredientOverlap
import fr.mescourses.core.menu.MenuComposer
import fr.mescourses.core.menu.MenuInsights
import fr.mescourses.core.menu.MenuPrompt
import fr.mescourses.core.menu.MenuTextParser
import fr.mescourses.core.menu.MenuWishes
import fr.mescourses.core.menu.Novelty
import fr.mescourses.core.menu.ShoppingPreview
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Portions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json


/** Ligne du fil de discussion : message de l'utilisateur, réponse du LLM, ou trace d'outil. */
data class ChatLine(val fromUser: Boolean, val text: String, val tool: Boolean = false, val ok: Boolean = true)

enum class MenuStep(val label: String) { REGLAGES("Envies"), PROPOSITION("Menu"), SEMAINE("Semaine") }

/** Envies proposées en un toucher (les mots cherchés sur les sites sont dans [CandidateFinder.QUICK_WISHES]). */
val QUICK_WISHES = CandidateFinder.QUICK_WISHES.keys.toList()

/** Nombre de recettes présentées au modèle : assez pour choisir, assez peu pour un petit modèle sur téléphone. */
private const val SHORTLIST = 18

data class MenuUiState(
    val meals: Int = 5,
    val adults: Int = 2,
    val children: Int = 0,
    val wishes: String = "",
    val time: CookingTime = CookingTime.NORMAL,
    val vegetarian: Int = 0,
    val fish: Int = 0,
    val meat: Int = 0,
    /** Ingrédients déjà à la maison, à utiliser en priorité. */
    val useUp: List<String> = emptyList(),
    val novelty: Novelty = Novelty.EQUILIBRE,
    val model: ModelStatus = ModelStatus.Absent,
    val busy: Boolean = false,
    /** Étape en cours de la composition (« Recherche sur 4 sites… »), affichée pendant que ça travaille. */
    val progress: String? = null,
    val chat: List<ChatLine> = emptyList(),
    val validation: Validation? = null,
    val message: String? = null,
)

class MenuViewModel(private val c: AppContainer) : ViewModel() {

    private companion object {
        const val OWNER = "menu"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(MenuUiState())
    val state: StateFlow<MenuUiState> = _state

    val step = MutableStateFlow(MenuStep.REGLAGES)
    val season = fr.mescourses.core.season.Seasonality.forMonth(java.time.LocalDate.now().month)

    val dishes: StateFlow<List<MenuDish>> = c.draft.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val week: StateFlow<List<PlannedMeal>> = c.plan.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Ingrédients partagés / isolés du brouillon. */
    val overlap: StateFlow<IngredientOverlap.Report> = c.draft.observe()
        .map { IngredientOverlap.compute(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IngredientOverlap.Report(emptyList(), emptyList()))

    /** Saison et composition du brouillon. */
    val insights: StateFlow<MenuInsights> = c.draft.observe()
        .map { MenuInsights.of(it, java.time.LocalDate.now().month) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MenuInsights(0, emptyList(), emptyList(), emptyMap()))

    /** Aperçu de la liste de courses du brouillon ; null tant qu'une recette n'est pas connue (plat inventé). */
    val shoppingPreview: StateFlow<ShoppingPreview?> = c.draft.observe()
        .map { dishes ->
            val factor = c.settings.current.childFactor
            val known = dishes.mapNotNull { d ->
                (d.recipeId?.let { c.recipes.get(it) } ?: c.candidates.of(d)?.recipe)?.let { it to d.portions.equivalent(factor) }
            }
            if (known.isNotEmpty() && known.size == dishes.size) ShoppingPreview.of(known, c.settings.current.pantryList()) else null
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Recettes écartées par « Autre recette » (adresse ou titre) : elles ne reviennent pas dans ce menu. */
    private val rejected = mutableSetOf<String>()
    private var searchRound = 0

    /** Ids des recettes favorites, pour l'étoile sur les cartes. */
    val favoriteIds: StateFlow<Set<Long>> = c.recipes.observeAll()
        .map { l -> l.filter { it.favorite }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var job: Job? = null
    /** Une discussion avec le chef est en cours (proposition ou ajustement). */
    @Volatile private var exchanging = false
    /** Tant que l'utilisateur n'a pas touché aux compteurs, ils suivent les valeurs par défaut des Réglages. */
    private var configTouched = false

    init {
        viewModelScope.launch {
            step.value = when {
                c.draft.get().isNotEmpty() -> MenuStep.PROPOSITION
                c.plan.all().isNotEmpty() -> MenuStep.SEMAINE
                else -> MenuStep.REGLAGES
            }
        }
        viewModelScope.launch {
            c.settings.state.collect { st ->
                if (!configTouched) {
                    // Compteurs du foyer, et habitudes du dernier formulaire (temps, composition, nouveautés).
                    val last = runCatching { json.decodeFromString(MenuWishes.serializer(), st.menuWishes) }.getOrNull()
                    _state.update {
                        it.copy(
                            meals = st.meals, adults = st.householdSize, children = st.children,
                            time = last?.time ?: it.time, novelty = last?.novelty ?: it.novelty,
                            vegetarian = last?.vegetarian ?: 0, fish = last?.fish ?: 0, meat = last?.meat ?: 0,
                        ).fitted()
                    }
                }
                c.model.refreshAvailability()
            }
        }
        viewModelScope.launch { c.model.status.collect { m -> _state.update { it.copy(model = m) } } }
        // La validation tourne au niveau de l'application : l'écran ne fait que l'observer.
        viewModelScope.launch {
            c.chef.validation.collect { v -> _state.update { it.copy(validation = v, busy = v != null || exchanging) } }
        }
        viewModelScope.launch {
            c.chef.result.collect { res ->
                if (res == null) return@collect
                c.chef.consumeResult()
                res.onSuccess { r ->
                    val msg = buildString {
                        append("${r.newRecipes + r.reused} plat(s) ajouté(s) au livre, ${r.shoppingItems} article(s) dans la liste.")
                        if (r.newRecipes > 0) append(" Les étapes s'écriront à l'ouverture de chaque recette.")
                        if (r.failures.isNotEmpty()) append(" Échec pour : ${r.failures.joinToString()} (reste dans le brouillon).")
                    }
                    _state.update { it.copy(message = msg, chat = emptyList()) }
                    if (r.failures.isEmpty()) step.value = MenuStep.SEMAINE
                }.onFailure { e ->
                    _state.update { it.copy(message = "Validation interrompue : ${e.message}") }
                }
            }
        }
    }

    private fun MenuUiState.toWishes() =
        MenuWishes(meals, Portions(adults, children), time, vegetarian, fish, meat, useUp, novelty, wishes).normalized()

    /** La composition ne dépasse jamais le nombre de repas. */
    private fun MenuUiState.fitted(): MenuUiState = toWishes().let { copy(vegetarian = it.vegetarian, fish = it.fish, meat = it.meat) }

    private fun config() = _state.value.toWishes()

    /** Charge (ou recharge après mise en veille) le modèle local. */
    fun loadModel() {
        viewModelScope.launch { c.model.ensureLoaded() }
    }

    fun setMeals(n: Int) { configTouched = true; _state.update { it.copy(meals = n.coerceIn(1, 21)).fitted() } }
    fun setTime(t: CookingTime) { configTouched = true; _state.update { it.copy(time = t) } }
    fun setNovelty(n: Novelty) { configTouched = true; _state.update { it.copy(novelty = n) } }

    /** Nombre de repas d'un type ; le total reste dans le nombre de repas. */
    fun setQuota(kind: DishKind, n: Int) {
        configTouched = true
        _state.update { s ->
            val others = s.vegetarian + s.fish + s.meat - s.toWishes().quota(kind)
            val v = n.coerceIn(0, (s.meals - others).coerceAtLeast(0))
            when (kind) {
                DishKind.VEGE -> s.copy(vegetarian = v)
                DishKind.POISSON -> s.copy(fish = v)
                DishKind.VIANDE -> s.copy(meat = v)
            }
        }
    }

    fun addUseUp(text: String) = _state.update { s ->
        val items = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        s.copy(useUp = (s.useUp + items).distinctBy { it.lowercase() }.take(6))
    }

    fun removeUseUp(item: String) = _state.update { it.copy(useUp = it.useUp - item) }
    fun setAdults(n: Int) { configTouched = true; _state.update { it.copy(adults = n.coerceIn(0, 20)) } }
    fun setChildren(n: Int) { configTouched = true; _state.update { it.copy(children = n.coerceIn(0, 20)) } }
    fun setWishes(text: String) = _state.update { it.copy(wishes = text) }

    fun toggleWish(wish: String) = _state.update { s ->
        val parts = s.wishes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.any { it.equals(wish, true) }) parts.removeAll { it.equals(wish, true) } else parts += wish
        s.copy(wishes = parts.joinToString(", "))
    }

    fun goTo(target: MenuStep) {
        val allowed = when (target) {
            MenuStep.REGLAGES -> true
            MenuStep.PROPOSITION -> dishes.value.isNotEmpty() || _state.value.busy
            MenuStep.SEMAINE -> week.value.isNotEmpty()
        }
        if (allowed) step.value = target
    }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun startConversation() {
        c.model.startConversation(OWNER, c.menuSystemPrompt(config()), c.menuTools())
    }

    private fun progress(text: String?) {
        _state.update { it.copy(progress = text) }
        if (text != null) c.chef.progress(text)
    }

    private fun chatError(e: Exception) =
        _state.update { it.copy(chat = it.chat + ChatLine(false, "⚠️ ${e.message}", tool = true, ok = false)) }

    /**
     * Compose le menu : recherche des recettes sur les sites, puis choix du chef (LLM) parmi les candidates.
     * Le compositeur complète ou remplace son choix s'il n'a pas répondu ; sans réseau, le chef invente les plats.
     */
    fun propose() {
        if (_state.value.busy) return
        val wishes = config()
        job = viewModelScope.launch {
            c.draft.set(emptyList())
            step.value = MenuStep.PROPOSITION
            _state.update { it.copy(chat = emptyList(), busy = true) }
            exchanging = true
            rejected.clear()
            searchRound = 0
            c.currentWishes = wishes
            try {
                c.settings.update { it.copy(menuWishes = json.encodeToString(MenuWishes.serializer(), wishes.copy(useUp = emptyList(), wishes = ""))) }
                c.chef.keepAwake("Le chef cherche des recettes…") {
                    val found = runCatching { c.candidateFinder.find(wishes) { progress(it) } }.getOrNull()
                    val list = found?.candidates.orEmpty()
                    // Les recettes que le compositeur retiendrait d'abord : la composition demandée y est toujours possible.
                    c.candidates.set((MenuComposer.compose(list, wishes) + list).distinct().take(SHORTLIST))
                    when {
                        list.isNotEmpty() -> chooseFromCandidates(wishes, found?.failedSites.orEmpty())
                        _state.value.model.available -> {
                            _state.update { it.copy(message = "Sites de recettes injoignables : le chef invente le menu.") }
                            inventMenu(wishes)
                        }
                        else -> {
                            step.value = MenuStep.REGLAGES
                            _state.update { it.copy(message = "Aucune recette trouvée : vérifie la connexion, puis réessaie.") }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                chatError(e)
            } finally {
                exchanging = false
                _state.update { it.copy(busy = c.chef.validating, progress = null) }
            }
        }
    }

    /** Le chef choisit parmi les candidates ; le compositeur garantit ensuite un menu complet. */
    private suspend fun chooseFromCandidates(wishes: MenuWishes, failedSites: List<String>) {
        if (_state.value.model.available) {
            progress("Le chef choisit parmi ${c.candidates.size} recettes…")
            try {
                startConversation()
                exchange(MenuPrompt.firstRequestWeb(wishes), shownAs = null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                chatError(e)
            }
        }
        val chosen = c.draft.get().take(wishes.meals)
        val missing = wishes.meals - chosen.size
        val extra = if (missing > 0) {
            MenuComposer.compose(c.candidates.all(), wishes, chosen.map(Chosen::of), chosen.mapNotNull { c.candidates.of(it) }.toSet())
        } else emptyList()
        c.draft.set(chosen + extra.map { it.toDish(wishes.portions) })
        val size = chosen.size + extra.size
        val notes = listOfNotNull(
            if (size < wishes.meals) "Seulement $size recette(s) trouvée(s) : ajoute des plats avec « Ajuster » ou « Créer une recette »." else null,
            if (failedSites.isNotEmpty()) "Sans réponse : ${failedSites.joinToString()}." else null,
        )
        if (notes.isNotEmpty()) _state.update { it.copy(message = notes.joinToString(" ")) }
    }

    /** Hors ligne : le chef invente les plats (les recettes seront écrites à la validation). */
    private suspend fun inventMenu(wishes: MenuWishes) {
        progress("Le chef compose ton menu…")
        val answer = try {
            startConversation()
            exchange(MenuPrompt.firstRequest(wishes), shownAs = null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            chatError(e)
            ""
        }
        // Plan B : le modèle a répondu en texte sans appeler propose_menu.
        if (c.draft.get().isEmpty()) {
            val parsed = MenuTextParser.parse(answer)
            if (parsed.isNotEmpty()) {
                c.draft.set(parsed.map { p -> MenuDish(p.title, p.description, wishes.portions, mainIngredients = p.ingredients, invented = true) })
            } else {
                step.value = MenuStep.REGLAGES
                _state.update { it.copy(message = "Le modèle n'a pas proposé de menu, réessaie.") }
            }
        }
    }

    /** Message libre pour ajuster le menu. */
    fun send(text: String, shownAs: String? = null) {
        val t = text.trim()
        if (t.isEmpty() || !_state.value.model.available || _state.value.busy) return
        job = viewModelScope.launch {
            exchanging = true
            _state.update { it.copy(busy = true) }
            try {
                c.chef.keepAwake("Le chef ajuste ton menu…") {
                    // Conversation neuve à chaque demande : le message porte déjà l'état du menu, et le contexte
                    // du modèle (limité sur téléphone) ne grossit plus d'un échange à l'autre.
                    startConversation()
                    exchange(MenuPrompt.context(c.draft.get()) + "\n" + t, shownAs = shownAs ?: t)
                }
            } catch (e: Exception) {
                _state.update { it.copy(chat = it.chat + ChatLine(false, "⚠️ ${e.message}", tool = true, ok = false)) }
            } finally {
                exchanging = false
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Autre recette des sites pour ce plat, sans passer par le modèle : la meilleure candidate restante compte tenu
     * des autres plats. Quand il n'y en a plus, une nouvelle recherche est lancée avec d'autres légumes de saison.
     */
    fun anotherRecipe(index: Int) {
        if (_state.value.busy) return
        job = viewModelScope.launch {
            exchanging = true
            _state.update { it.copy(busy = true) }
            try {
                c.chef.keepAwake("Le chef cherche une autre recette…") { swap(index) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = "Recherche impossible : ${e.message}") }
            } finally {
                exchanging = false
                _state.update { it.copy(busy = c.chef.validating, progress = null) }
            }
        }
    }

    private suspend fun swap(index: Int) {
                val wishes = c.currentWishes ?: config().also { c.currentWishes = it }
                val dishes = c.draft.get()
                val current = dishes.getOrNull(index) ?: return
                rejected += current.sourceUrl ?: current.title
                val others = dishes.filterIndexed { i, _ -> i != index }

                fun pick() = c.candidates.all().let { pool ->
                    val taken = (dishes.mapNotNull { c.candidates.of(it) } + pool.filter { it.recipe.sourceUrl in rejected || it.recipe.title in rejected }).toSet()
                    MenuComposer.replacement(pool, wishes, others.map(Chosen::of), taken)
                }

                val next = pick() ?: run {
                    progress("Recherche d'autres recettes…")
                    val seen = rejected + c.candidates.all().mapNotNull { it.recipe.sourceUrl }
                    val more = runCatching { c.candidateFinder.find(wishes, round = ++searchRound, exclude = seen) { progress(it) } }.getOrNull()
                    c.candidates.add(more?.candidates.orEmpty())
                    pick()
                }
                if (next == null) {
                    _state.update { it.copy(message = "Pas d'autre recette trouvée. Demande au chef d'en créer une, ou cherche avec « Ajuster ».") }
                } else {
                    c.draft.set(c.draft.get().toMutableList().also { if (index < it.size) it[index] = next.toDish(current.portions) })
                }
    }

    /** Recette créée par le chef, à la place du plat [index] (ou en plus si null). [idea] : ce que l'utilisateur veut. */
    fun invent(index: Int?, idea: String) {
        val text = idea.trim()
        send(MenuPrompt.inventRequest(text, index?.plus(1)), shownAs = "Invente une recette" + if (text.isNotEmpty()) " : $text" else "")
    }

    /**
     * « Mieux mutualiser ». Avec des recettes des sites : échange calculé par le compositeur (immédiat, sans modèle).
     * Sans candidates (plats inventés hors ligne) : on demande au chef.
     */
    fun improveSharing() {
        val pool = c.candidates.all()
        if (pool.isEmpty()) { send(MenuPrompt.IMPROVE_SHARING); return }
        if (_state.value.busy) return
        viewModelScope.launch {
            val dishes = c.draft.get()
            val wishes = c.currentWishes ?: config()
            val swap = MenuComposer.improveSharing(
                pool, wishes, dishes.map { d -> Chosen.of(d) to c.candidates.of(d) },
                exclude = pool.filter { it.recipe.sourceUrl in rejected || it.recipe.title in rejected }.toSet(),
            )
            if (swap == null) {
                _state.update { it.copy(message = "Le menu partage déjà bien ses ingrédients : aucune recette trouvée ne ferait mieux.") }
            } else {
                val old = dishes[swap.index]
                rejected += old.sourceUrl ?: old.title
                c.draft.set(dishes.toMutableList().also { it[swap.index] = swap.candidate.toDish(old.portions) })
                _state.update {
                    it.copy(message = "« ${old.title} » remplacé par « ${swap.candidate.recipe.title} » : ${swap.sharedAfter} ingrédients partagés avec les autres plats (au lieu de ${swap.sharedBefore}).")
                }
            }
        }
    }

    /** Marqueurs internes du modèle (« <channel|> », « <start_of_turn> »…) qui n'ont rien à faire dans une bulle. */
    private val modelMarkers = Regex("""<[^<>\s]*\|[^<>\s]*>|</?(start|end)_of_turn>""")

    private fun isContextFull(e: Exception) =
        e.message.orEmpty().let { "exceeds available" in it || "state entries" in it || "context" in it.lowercase() && "exceed" in it.lowercase() }

    /** Envoie un message au LLM et suit les appels d'outils ; renvoie le texte de la réponse. */
    private suspend fun exchange(prompt: String, shownAs: String?, retry: Boolean = true): String {
        _state.update { s -> s.copy(busy = true, chat = if (shownAs != null) s.chat + ChatLine(true, shownAs) else s.chat) }
        val answer = StringBuilder()
        try {
            c.model.use { c.model.send(prompt).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> {
                        answer.append(ev.text)
                        _state.update { s ->
                            val chat = s.chat.toMutableList()
                            val last = chat.lastOrNull()
                            if (last != null && !last.fromUser && !last.tool) chat[chat.lastIndex] = last.copy(text = (last.text + ev.text).replace(modelMarkers, ""))
                            else chat += ChatLine(false, ev.text.replace(modelMarkers, ""))
                            s.copy(chat = chat)
                        }
                    }
                    is ChatEvent.ToolCall -> Unit
                    is ChatEvent.ToolResult -> _state.update { s ->
                        s.copy(chat = s.chat + ChatLine(false, (if (ev.ok) "✅ " else "⚠️ ") + ev.summary, tool = true, ok = ev.ok))
                    }
                }
            } }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (isContextFull(e)) {
                // Mémoire de travail du modèle pleine : on repart d'une conversation vide, une fois.
                if (retry) {
                    startConversation()
                    return exchange(prompt, shownAs = null, retry = false)
                }
                _state.update { it.copy(chat = it.chat + ChatLine(false, "⚠️ Demande trop longue pour l'assistant du téléphone : reformule-la plus court, ou utilise les boutons des plats.", tool = true, ok = false)) }
            } else {
                chatError(e)
            }
        }
        return answer.toString()
    }

    fun stop() {
        job?.cancel()
        exchanging = false
        _state.update { it.copy(busy = c.chef.validating) }
    }

    // --- Modifications manuelles du brouillon ---

    fun changePortions(index: Int, adultsDelta: Int, childrenDelta: Int) = viewModelScope.launch {
        val list = c.draft.get().toMutableList()
        val p = list[index].portions
        list[index] = list[index].copy(portions = Portions((p.adults + adultsDelta).coerceAtLeast(0), (p.children + childrenDelta).coerceAtLeast(0)))
        c.draft.set(list)
    }

    fun removeDish(index: Int) = viewModelScope.launch {
        c.draft.set(c.draft.get().toMutableList().also { it.removeAt(index) })
    }

    fun clearDraft() = viewModelScope.launch {
        c.draft.set(emptyList())
        _state.update { it.copy(chat = emptyList()) }
        step.value = if (week.value.isNotEmpty()) MenuStep.SEMAINE else MenuStep.REGLAGES
    }

    /** Génère les recettes, remplit le livre (Historique) et la liste de courses. */
    fun validate() {
        if (_state.value.busy || dishes.value.isEmpty()) return
        // Les plats venus d'un site ont déjà leur recette : seules les recettes inventées passent par le modèle.
        val needsModel = dishes.value.any { it.recipeId == null && it.sourceUrl == null }
        if (needsModel && !_state.value.model.available) {
            _state.update { it.copy(message = "Le modèle doit être chargé pour écrire les recettes.") }
            return
        }
        c.chef.validate(c.menuValidator(), dishes.value.size)
    }

    // --- Menu de la semaine validé ---

    fun toggleCooked(meal: PlannedMeal) = viewModelScope.launch { c.plan.update(meal.copy(cooked = !meal.cooked)) }
    fun clearWeek() = viewModelScope.launch {
        c.plan.clear()
        step.value = MenuStep.REGLAGES
    }

    fun newMenu() { step.value = MenuStep.REGLAGES }
}

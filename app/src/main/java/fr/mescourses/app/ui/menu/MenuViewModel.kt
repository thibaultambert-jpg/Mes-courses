package fr.mescourses.app.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.llm.ChatEvent
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.work.Validation
import fr.mescourses.core.menu.Candidate
import fr.mescourses.core.menu.CandidateFinder
import fr.mescourses.core.menu.MenuCommand
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
    /** Une retouche peut être annulée (le menu d'avant est gardé). */
    val canUndo: Boolean = false,
    /** Recettes proposées par « Ajouter un plat » ; null = fenêtre fermée. */
    val picker: List<Candidate>? = null,
    val pickerBusy: Boolean = false,
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

    /** Recettes du livre qui attendent leurs étapes (le chef ne les écrit que sur demande). */
    val pendingSteps: StateFlow<Int> = kotlinx.coroutines.flow.combine(c.recipes.observeAll(), c.plan.observeAll()) { list, plan ->
        // Seulement les recettes de la semaine en cours : une ancienne recette du livre n'y compte pas.
        val ids = plan.map { it.recipeId }.toSet()
        val w = c.stepsWriter()
        list.count { it.id in ids && w.needsSteps(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Avancement de l'écriture des étapes (faites, total) ; null = rien en cours. */
    val stepsProgress: StateFlow<Pair<Int, Int>?> = kotlinx.coroutines.flow.combine(c.chef.stepsQueue, c.chef.batchTotal) { queue, total ->
        if (queue.isEmpty()) null else (total - queue.size).coerceAtLeast(0) to maxOf(total, queue.size)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun stopGeneratingSteps() = c.chef.cancelSteps()

    fun generateMissingSteps() { c.chef.enqueueSteps(week.value.map { it.recipeId }.toSet()) }

    /** Bloc « Bienvenue » replié (mémorisé). */
    val welcomeCollapsed: StateFlow<Boolean> = c.settings.state.map { it.welcomeCollapsed }.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.current.welcomeCollapsed)

    fun setWelcomeCollapsed(v: Boolean) { viewModelScope.launch { c.settings.update { it.copy(welcomeCollapsed = v) } } }

    /** Image de chaque recette du livre (pour l'écran de la semaine). */
    val recipeImages: StateFlow<Map<Long, String>> = c.recipes.observeAll()
        .map { list -> list.mapNotNull { r -> r.imageUrl?.let { r.id to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
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
    /** Recettes déjà proposées par les menus précédents de cette session : « recommencer » en sort d'autres. */
    private val proposed = mutableSetOf<String>()

    private fun titleKey(title: String) = fr.mescourses.core.shopping.ShoppingListMerger.key(title)

    /** Écarte un plat de ce menu, par son adresse et par son titre (un site publie parfois la même recette deux fois). */
    private fun reject(dish: MenuDish) {
        dish.sourceUrl?.let { rejected += it }
        rejected += dish.title
    }

    /** Recette à ne pas proposer : écartée, ou déjà au menu (même adresse ou même titre). */
    private fun blocked(c: Candidate, dishes: List<MenuDish>): Boolean {
        val title = titleKey(c.recipe.title)
        return c.recipe.sourceUrl in rejected || rejected.any { titleKey(it) == title } ||
            dishes.any { d -> (d.sourceUrl != null && d.sourceUrl == c.recipe.sourceUrl) || titleKey(d.title) == title }
    }

    private fun draw() = kotlin.random.Random.nextInt(1, 100_000)

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
                        if (r.newRecipes > 0) append(" Les étapes manquantes se génèrent sur demande (bouton du chef).")
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

    /** [adjusting] : retouche d'un menu existant (le modèle n'a alors pas l'outil qui réécrit tout le menu). */
    private suspend fun startConversation(adjusting: Boolean = false, maxChanges: Int? = this.maxChanges) {
        this.adjusting = adjusting
        this.maxChanges = maxChanges
        c.model.startConversation(OWNER, c.menuSystemPrompt(config(), adjusting), c.menuTools(adjusting, maxChanges))
    }
    private var maxChanges: Int? = null
    private var adjusting = false

    /** Menu d'avant la dernière retouche, pour « Annuler ». */
    private var before: List<MenuDish>? = null

    private suspend fun snapshot() {
        before = c.draft.get()
        _state.update { it.copy(canUndo = true) }
    }

    fun undo() = viewModelScope.launch {
        val old = before ?: return@launch
        before = null
        c.draft.set(old)
        _state.update { it.copy(canUndo = false, chat = it.chat + ChatLine(false, "↩ Dernière retouche annulée", tool = true)) }
    }

    private fun say(text: String, ok: Boolean = true) =
        _state.update { it.copy(chat = it.chat + ChatLine(false, (if (ok) "✅ " else "⚠️ ") + text, tool = true, ok = ok)) }

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
                    // Tirage au sort et légumes de saison qui tournent à chaque demande ; les recettes des menus
                    // précédents sont laissées de côté tant qu'il en reste assez d'autres.
                    val round = draw()
                    val seed = draw()
                    val found = runCatching { c.candidateFinder.find(wishes, round = round % 7, exclude = proposed, seed = seed) { progress(it) } }.getOrNull()
                        ?.takeIf { it.candidates.size >= wishes.meals * 2 }
                        ?: runCatching { c.candidateFinder.find(wishes, round = round % 7, seed = seed) { progress(it) } }.getOrNull()
                    val list = found?.candidates.orEmpty().shuffled().sortedByDescending { (it.base * 2).toInt() }
                    // Les recettes que le compositeur retiendrait d'abord : la composition demandée y est toujours possible.
                    // … puis les autres, en alternant les sites pour que le chef ait vraiment le choix.
                    val bySite = fr.mescourses.core.recipes.RecipeSearcher.interleave(list.groupBy { it.siteName }.values.toList())
                    c.candidates.set((MenuComposer.compose(list, wishes) + bySite).distinct().take(SHORTLIST))
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
        proposed += (chosen + extra.map { it.toDish(wishes.portions) }).mapNotNull { it.sourceUrl }
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
        if (t.isEmpty() || _state.value.busy) return
        // Demande claire (convives, ajouter, retirer, remplacer un plat) : l'appli l'exécute elle-même, sans le modèle.
        val command = if (shownAs == null) MenuCommand.parse(t, dishes.value.size) else null
        if (command == null && !_state.value.model.available) {
            _state.update { it.copy(chat = it.chat + ChatLine(true, shownAs ?: t)) }
            say("L'assistant n'est pas chargé. Sans lui, je comprends : « ajoute 2 adultes au plat 1 », « retire le plat 3 », « ajoute un plat », « remplace le plat 2 ».", ok = false)
            return
        }
        job = viewModelScope.launch {
            exchanging = true
            _state.update { it.copy(busy = true) }
            try {
                snapshot()
                if (command != null) {
                    _state.update { it.copy(chat = it.chat + ChatLine(true, t)) }
                    c.chef.keepAwake("Le chef ajuste ton menu…") { execute(command) }
                } else c.chef.keepAwake("Le chef ajuste ton menu…") {
                    // Conversation neuve à chaque demande : le message porte déjà l'état du menu, et le contexte
                    // du modèle (limité sur téléphone) ne grossit plus d'un échange à l'autre.
                    // Un plat est nommé : une seule retouche permise. Demande générale (« plus végétarien ») : trois au plus.
                    val oneDish = Regex("""\bplat\s*\d|\b\d+\s*(er|e|[eè]me)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                    startConversation(adjusting = c.candidates.size > 0, maxChanges = if (oneDish) 1 else 3)
                    exchange(MenuPrompt.context(c.draft.get()) + "\n" + t, shownAs = shownAs ?: t)
                }
            } catch (e: Exception) {
                _state.update { it.copy(chat = it.chat + ChatLine(false, "⚠️ ${e.message}", tool = true, ok = false)) }
            } finally {
                exchanging = false
                _state.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    private fun wishes() = c.currentWishes ?: config().also { c.currentWishes = it }

    /** Meilleure recette disponible à ajouter au menu, compte tenu des plats déjà là ; cherche au besoin. */
    private suspend fun bestToAdd(): Candidate? {
        val wishes = wishes()
        suspend fun pick(): Candidate? {
            val dishes = c.draft.get()
            val pool = c.candidates.all()
            return MenuComposer.replacement(pool, wishes, dishes.map(Chosen::of), pool.filter { blocked(it, dishes) }.toSet())
        }
        return pick() ?: run {
            progress("Recherche d'autres recettes…")
            val seen = rejected + c.candidates.all().mapNotNull { it.recipe.sourceUrl }
            val more = runCatching { c.candidateFinder.find(wishes, round = ++searchRound, exclude = seen, seed = draw()) { progress(it) } }.getOrNull()
            c.candidates.add(more?.candidates.orEmpty())
            pick()
        }
    }

    /** Première recette trouvée pour des mots donnés qui n'est pas déjà au menu. */
    private suspend fun searched(words: String?, site: String? = null, except: Int? = null): Candidate? {
        val siteName = site?.let { fr.mescourses.core.recipes.RecipeSites.byId(it)?.name }
        progress("Recherche" + (words?.let { " de « $it »" } ?: "") + (siteName?.let { " sur $it" } ?: "") + "…")
        val dishes = c.draft.get().filterIndexed { i, _ -> i != except }
        val found = runCatching {
            c.candidateFinder.search(words.orEmpty(), wishes(), limit = 12, exclude = rejected, sites = setOfNotNull(site), seed = draw())
        }.getOrNull().orEmpty().filterNot { blocked(it, dishes) }
        if (found.isEmpty()) return null
        // Parmi les recettes trouvées, celle qui va le mieux avec le reste du menu.
        val best = MenuComposer.replacement(found, wishes(), dishes.map(Chosen::of), emptySet()) ?: found.first()
        c.candidates.add(listOf(best))
        return best
    }

    /** Exécute une demande comprise sans le modèle, et dit ce qui a été fait. */
    private suspend fun execute(command: MenuCommand) {
        val list = c.draft.get()
        when (command) {
            is MenuCommand.AddGuests -> {
                val d = list[command.dish]
                val p = Portions(d.portions.adults + command.adults, d.portions.children + command.children)
                c.draft.set(list.toMutableList().also { it[command.dish] = d.copy(portions = p) })
                say("Plat ${command.dish + 1} « ${d.title} » : ${p.label()}")
            }
            is MenuCommand.SetPeople -> {
                val d = list[command.dish]
                val p = Portions(command.adults, command.children)
                c.draft.set(list.toMutableList().also { it[command.dish] = d.copy(portions = p) })
                say("Plat ${command.dish + 1} « ${d.title} » : ${p.label()}")
            }
            is MenuCommand.Remove -> {
                c.draft.set(list.toMutableList().also { it.removeAt(command.dish) })
                say("« ${list[command.dish].title} » retiré")
            }
            is MenuCommand.AddDish -> {
                val next = if (command.words != null || command.site != null) searched(command.words, command.site) else bestToAdd()
                if (next == null) say("Aucune recette trouvée" + (command.words?.let { " pour « $it »" } ?: "") + ". Essaie « Ajouter un plat » pour chercher toi-même.", ok = false)
                else {
                    c.draft.set(c.draft.get() + next.toDish(wishes().portions))
                    say("« ${next.recipe.title} » ajouté (plat ${list.size + 1})")
                }
            }
            is MenuCommand.Replace -> {
                val old = list[command.dish]
                val words = command.words
                if (words == null && command.site == null) {
                    swap(command.dish)
                    val now = c.draft.get().getOrNull(command.dish)
                    if (now != null && now.title != old.title) say("Plat ${command.dish + 1} remplacé par « ${now.title} »") else say("Pas d'autre recette trouvée pour le plat ${command.dish + 1}.", ok = false)
                } else {
                    val next = searched(words, command.site, except = command.dish)
                    if (next == null) say("Aucune recette trouvée" + (words?.let { " pour « $it »" } ?: "") + (command.site?.let { " sur ce site" } ?: "") + ".", ok = false)
                    else {
                        reject(old)
                        c.draft.set(list.toMutableList().also { it[command.dish] = next.toDish(old.portions) })
                        say("Plat ${command.dish + 1} remplacé par « ${next.recipe.title} »")
                    }
                }
            }
        }
    }

    // --- « Ajouter un plat » : choix parmi les recettes disponibles ---

    private fun available(): List<Candidate> {
        return c.candidates.all().filterNot { blocked(it, dishes.value) }
    }

    /** Ouvre la liste des recettes disponibles (celles trouvées pour ce menu ; cherchées si l'appli a été relancée). */
    fun openPicker() {
        _state.update { it.copy(picker = available()) }
        if (available().size >= 4) return
        viewModelScope.launch {
            _state.update { it.copy(pickerBusy = true) }
            val more = runCatching { c.candidateFinder.find(wishes(), round = ++searchRound, exclude = rejected, seed = draw()) }.getOrNull()
            c.candidates.add(more?.candidates.orEmpty())
            _state.update { s -> s.copy(pickerBusy = false, picker = s.picker?.let { available() }) }
        }
    }

    fun closePicker() = _state.update { it.copy(picker = null, pickerBusy = false) }

    /** Recherche libre depuis la fenêtre : les résultats passent en tête de liste. */
    fun searchPicker(text: String) {
        val words = text.trim()
        if (words.isEmpty()) { _state.update { it.copy(picker = available()) }; return }
        viewModelScope.launch {
            _state.update { it.copy(pickerBusy = true) }
            val found = runCatching { c.candidateFinder.search(words, wishes(), limit = 12, exclude = rejected) }.getOrNull().orEmpty()
            c.candidates.add(found)
            _state.update { s -> s.copy(pickerBusy = false, picker = s.picker?.let { found.filterNot { blocked(it, dishes.value) } }) }
            if (found.isEmpty()) _state.update { it.copy(message = "Aucune recette trouvée pour « $words ».") }
        }
    }

    fun addCandidate(candidate: Candidate) = viewModelScope.launch {
        snapshot()
        c.candidates.add(listOf(candidate))
        c.draft.set(c.draft.get() + candidate.toDish(wishes().portions))
        _state.update { it.copy(picker = null, message = "« ${candidate.recipe.title} » ajouté au menu.") }
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
                reject(current)
                val others = dishes.filterIndexed { i, _ -> i != index }

                fun pick() = c.candidates.all().let { pool ->
                    MenuComposer.replacement(pool, wishes, others.map(Chosen::of), pool.filter { blocked(it, others) }.toSet())
                }

                val next = pick() ?: run {
                    progress("Recherche d'autres recettes…")
                    val seen = rejected + c.candidates.all().mapNotNull { it.recipe.sourceUrl }
                    val more = runCatching { c.candidateFinder.find(wishes, round = ++searchRound, exclude = seen, seed = draw()) { progress(it) } }.getOrNull()
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
                exclude = pool.filter { blocked(it, dishes) }.toSet(),
            )
            if (swap == null) {
                _state.update { it.copy(message = "Le menu partage déjà bien ses ingrédients : aucune recette trouvée ne ferait mieux.") }
            } else {
                val old = dishes[swap.index]
                reject(old)
                c.draft.set(dishes.toMutableList().also { it[swap.index] = swap.candidate.toDish(old.portions) })
                _state.update {
                    it.copy(message = "« ${old.title} » remplacé par « ${swap.candidate.recipe.title} » : ${swap.sharedAfter} ingrédients partagés avec les autres plats (au lieu de ${swap.sharedBefore}).")
                }
            }
        }
    }

    /** Marqueurs internes du modèle (« <channel|> », « <start_of_turn> »…) qui n'ont rien à faire dans une bulle. */
    private val modelMarkers = Regex("""<[^<>\s]*\|[^<>\s]*>|</?(start|end)_of_turn>""")

    /** Le modèle a écrit un appel d'outil illisible (« [1,2,] ») : fréquent sur un petit modèle, on redemande. */
    private fun isBadToolCall(e: Exception) = "parse tool call" in e.message.orEmpty().lowercase()

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
            if (isBadToolCall(e)) {
                if (retry) {
                    startConversation(adjusting)
                    return exchange(prompt, shownAs = null, retry = false)
                }
                say("Le chef n'a pas réussi à faire cette retouche. Reformule plus simplement (un seul changement à la fois), ou utilise les boutons des plats.", ok = false)
            } else if (isContextFull(e)) {
                // Mémoire de travail du modèle pleine : on repart d'une conversation vide, une fois.
                if (retry) {
                    startConversation(adjusting)
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
        before = null
        _state.update { it.copy(chat = emptyList(), canUndo = false) }
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
        // Les recettes qui attendaient leurs étapes ne doivent pas rester en file pour le prochain menu.
        c.chef.cancelSteps()
        c.plan.clear()
        step.value = MenuStep.REGLAGES
    }

    fun newMenu() { step.value = MenuStep.REGLAGES }
}

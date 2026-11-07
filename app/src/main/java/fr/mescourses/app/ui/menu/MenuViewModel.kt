package fr.mescourses.app.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.llm.ChatEvent
import fr.mescourses.core.menu.IngredientOverlap
import fr.mescourses.core.menu.MenuConfig
import fr.mescourses.core.menu.MenuPrompt
import fr.mescourses.core.menu.MenuTextParser
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
import java.io.File

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data object Loading : ModelStatus
    data class Ready(val backend: String, val modelName: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/** Ligne du fil de discussion : message de l'utilisateur, réponse du LLM, ou trace d'outil. */
data class ChatLine(val fromUser: Boolean, val text: String, val tool: Boolean = false, val ok: Boolean = true)

data class Validation(val done: Int, val total: Int, val current: String)

data class MenuUiState(
    val meals: Int = 5,
    val adults: Int = 2,
    val children: Int = 0,
    val wishes: String = "",
    val model: ModelStatus = ModelStatus.Absent,
    val busy: Boolean = false,
    val chat: List<ChatLine> = emptyList(),
    val validation: Validation? = null,
    val message: String? = null,
)

class MenuViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MenuUiState())
    val state: StateFlow<MenuUiState> = _state

    val dishes: StateFlow<List<MenuDish>> = c.draft.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val week: StateFlow<List<PlannedMeal>> = c.plan.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Ingrédients partagés / isolés du brouillon. */
    val overlap: StateFlow<IngredientOverlap.Report> = c.draft.observe()
        .map { IngredientOverlap.compute(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IngredientOverlap.Report(emptyList(), emptyList()))

    /** Ids des recettes favorites, pour l'étoile sur les cartes. */
    val favoriteIds: StateFlow<Set<Long>> = c.recipes.observeAll()
        .map { l -> l.filter { it.favorite }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var job: Job? = null
    private var loadedPath: String? = null
    /** Tant que l'utilisateur n'a pas touché aux compteurs, ils suivent les valeurs par défaut des Réglages. */
    private var configTouched = false

    init {
        viewModelScope.launch {
            c.settings.state.collect { st ->
                if (!configTouched) _state.update { it.copy(meals = st.meals, adults = st.householdSize, children = st.children) }
                if (st.modelPath.isNotBlank() && st.modelPath != loadedPath && File(st.modelPath).exists()) loadModel(st.modelPath)
            }
        }
    }

    private fun config() = _state.value.let { MenuConfig(it.meals, Portions(it.adults, it.children), it.wishes) }

    fun loadModel(path: String = c.settings.current.modelPath) {
        if (path.isBlank() || !File(path).exists()) {
            _state.update { it.copy(model = ModelStatus.Absent) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(model = ModelStatus.Loading) }
            try {
                val backend = c.llm.load(path, c.settings.current.preferGpu)
                loadedPath = path
                _state.update { it.copy(model = ModelStatus.Ready(backend, File(path).nameWithoutExtension)) }
            } catch (e: Exception) {
                _state.update { it.copy(model = ModelStatus.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun setMeals(n: Int) { configTouched = true; _state.update { it.copy(meals = n.coerceIn(1, 21)) } }
    fun setAdults(n: Int) { configTouched = true; _state.update { it.copy(adults = n.coerceIn(0, 20)) } }
    fun setChildren(n: Int) { configTouched = true; _state.update { it.copy(children = n.coerceIn(0, 20)) } }
    fun setWishes(text: String) = _state.update { it.copy(wishes = text) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun startConversation() {
        c.llm.newConversation(c.menuSystemPrompt(config()), c.menuTools())
    }

    /** Lance la proposition d'un menu complet. */
    fun propose() {
        if (_state.value.model !is ModelStatus.Ready || _state.value.busy) return
        job = viewModelScope.launch {
            c.draft.set(emptyList())
            _state.update { it.copy(chat = emptyList()) }
            startConversation()
            val answer = exchange(MenuPrompt.firstRequest(config()), shownAs = null)
            // Plan B : le modèle a répondu en texte sans appeler propose_menu.
            if (c.draft.get().isEmpty()) {
                val parsed = MenuTextParser.parse(answer)
                if (parsed.isNotEmpty()) {
                    c.draft.set(
                        parsed.map { p ->
                            MenuDish(p.title, p.description, Portions(_state.value.adults, _state.value.children), mainIngredients = p.ingredients)
                        },
                    )
                } else {
                    _state.update { it.copy(message = "Le modèle n'a pas proposé de menu, réessaie.") }
                }
            }
        }
    }

    /** Message libre pour ajuster le menu. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _state.value.model !is ModelStatus.Ready || _state.value.busy) return
        job = viewModelScope.launch {
            if (!c.llm.hasConversation()) startConversation()
            exchange(MenuPrompt.context(c.draft.get()) + "\n" + t, shownAs = t)
        }
    }

    fun anotherIdea(index: Int) =
        send("Remplace le plat ${index + 1} par une autre idée, qui réutilise si possible des ingrédients des autres plats.")

    fun improveSharing() = send(MenuPrompt.IMPROVE_SHARING)

    /** Envoie un message au LLM et suit les appels d'outils ; renvoie le texte de la réponse. */
    private suspend fun exchange(prompt: String, shownAs: String?): String {
        _state.update { s -> s.copy(busy = true, chat = if (shownAs != null) s.chat + ChatLine(true, shownAs) else s.chat) }
        val answer = StringBuilder()
        try {
            c.llm.send(prompt).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> {
                        answer.append(ev.text)
                        _state.update { s ->
                            val chat = s.chat.toMutableList()
                            val last = chat.lastOrNull()
                            if (last != null && !last.fromUser && !last.tool) chat[chat.lastIndex] = last.copy(text = last.text + ev.text)
                            else chat += ChatLine(false, ev.text)
                            s.copy(chat = chat)
                        }
                    }
                    is ChatEvent.ToolCall -> Unit
                    is ChatEvent.ToolResult -> _state.update { s ->
                        s.copy(chat = s.chat + ChatLine(false, (if (ev.ok) "✅ " else "⚠️ ") + ev.summary, tool = true, ok = ev.ok))
                    }
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(chat = it.chat + ChatLine(false, "⚠️ ${e.message}", tool = true, ok = false)) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
        return answer.toString()
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(busy = false) }
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
    }

    /** Génère les recettes, remplit le livre (Historique) et la liste de courses. */
    fun validate() {
        if (_state.value.busy || dishes.value.isEmpty()) return
        val needsModel = dishes.value.any { it.recipeId == null }
        if (needsModel && _state.value.model !is ModelStatus.Ready) {
            _state.update { it.copy(message = "Le modèle doit être chargé pour écrire les recettes.") }
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, validation = Validation(0, dishes.value.size, "")) }
            try {
                val r = c.menuValidator().validate { done, total, current ->
                    _state.update { it.copy(validation = Validation(done, total, current)) }
                }
                val msg = buildString {
                    append("${r.newRecipes + r.reused} plat(s) ajouté(s) au livre, ${r.shoppingItems} article(s) dans la liste.")
                    if (r.failures.isNotEmpty()) append(" Échec pour : ${r.failures.joinToString()} (reste dans le brouillon).")
                }
                _state.update { it.copy(message = msg, chat = emptyList()) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Validation interrompue : ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false, validation = null) }
            }
        }
    }

    // --- Menu de la semaine validé ---

    fun toggleCooked(meal: PlannedMeal) = viewModelScope.launch { c.plan.update(meal.copy(cooked = !meal.cooked)) }
    fun clearWeek() = viewModelScope.launch { c.plan.clear() }
}

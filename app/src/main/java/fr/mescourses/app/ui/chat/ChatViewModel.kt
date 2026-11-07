package fr.mescourses.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.llm.ChatEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Role { USER, ASSISTANT, TOOL }

data class UiMessage(val role: Role, val text: String, val ok: Boolean = true)

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data object Loading : ModelStatus
    data class Ready(val backend: String, val modelName: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

data class ChatState(
    val messages: List<UiMessage> = emptyList(),
    val generating: Boolean = false,
    val model: ModelStatus = ModelStatus.Absent,
    val tokensPerSecond: Double? = null,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state
    private var job: Job? = null
    private var loadedPath: String? = null

    init {
        viewModelScope.launch {
            c.settings.state.collect { s ->
                if (s.modelPath.isNotBlank() && s.modelPath != loadedPath && File(s.modelPath).exists()) load(s.modelPath)
            }
        }
    }

    fun load(path: String = c.settings.current.modelPath) {
        if (path.isBlank() || !File(path).exists()) {
            _state.update { it.copy(model = ModelStatus.Absent) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(model = ModelStatus.Loading) }
            try {
                val backend = c.llm.load(path, c.settings.current.preferGpu)
                loadedPath = path
                c.llm.newConversation(c.systemPrompt(), c.agentTools())
                _state.update { it.copy(model = ModelStatus.Ready(backend, File(path).nameWithoutExtension), messages = emptyList()) }
            } catch (e: Exception) {
                _state.update { it.copy(model = ModelStatus.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun newConversation() {
        job?.cancel()
        if (!c.llm.isLoaded()) return
        c.llm.newConversation(c.systemPrompt(), c.agentTools())
        _state.update { it.copy(messages = emptyList(), generating = false, tokensPerSecond = null) }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.generating || _state.value.model !is ModelStatus.Ready) return
        _state.update { it.copy(messages = it.messages + UiMessage(Role.USER, message), generating = true) }
        job = viewModelScope.launch {
            val start = System.nanoTime()
            var chunks = 0
            var answer = StringBuilder()
            fun publishAnswer() = _state.update { s ->
                val msgs = s.messages.toMutableList()
                if (msgs.lastOrNull()?.role == Role.ASSISTANT) msgs[msgs.lastIndex] = UiMessage(Role.ASSISTANT, answer.toString())
                else msgs += UiMessage(Role.ASSISTANT, answer.toString())
                s.copy(messages = msgs)
            }
            try {
                c.llm.send(message).collect { ev ->
                    when (ev) {
                        is ChatEvent.Token -> {
                            chunks++
                            answer.append(ev.text)
                            publishAnswer()
                            val secs = (System.nanoTime() - start) / 1e9
                            if (secs > 0.5) _state.update { it.copy(tokensPerSecond = chunks / secs) }
                        }
                        is ChatEvent.ToolCall -> {
                            answer = StringBuilder() // le texte suivant l'outil forme une nouvelle bulle
                            _state.update { it.copy(messages = it.messages + UiMessage(Role.TOOL, "🔧 ${label(ev.name)}…")) }
                        }
                        is ChatEvent.ToolResult -> _state.update { s ->
                            val msgs = s.messages.toMutableList()
                            val i = msgs.indexOfLast { it.role == Role.TOOL }
                            if (i >= 0) msgs[i] = UiMessage(Role.TOOL, "${if (ev.ok) "✅" else "⚠️"} ${label(ev.name)} — ${ev.summary}", ev.ok)
                            s.copy(messages = msgs)
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(messages = it.messages + UiMessage(Role.TOOL, "⚠️ Erreur : ${e.message}", ok = false)) }
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(generating = false) }
    }

    private fun label(tool: String) = when (tool) {
        "search_cookbook" -> "Recherche dans le livre de recettes"
        "save_recipe" -> "Enregistrement de la recette"
        "search_recipes_web" -> "Recherche de recettes sur internet"
        "get_recipe_from_url" -> "Lecture de la recette"
        "plan_meal" -> "Planification"
        "get_meal_plan" -> "Lecture du planning"
        "add_recipe_to_shopping_list" -> "Ajout des ingrédients à la liste"
        "add_to_shopping_list" -> "Ajout à la liste"
        "get_shopping_list" -> "Lecture de la liste"
        "retailer_search_product" -> "Recherche au drive"
        else -> tool
    }
}

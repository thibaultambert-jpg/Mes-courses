package fr.mescourses.app.llm

import fr.mescourses.app.data.SettingsStore
import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.llm.CarryOver
import fr.mescourses.core.llm.ContextBudget
import fr.mescourses.core.llm.Msg
import fr.mescourses.core.llm.MsgRole
import fr.mescourses.core.llm.TokenEstimate
import fr.mescourses.core.llm.Transcript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data object Loading : ModelStatus
    data class Ready(val backend: String, val modelName: String) : ModelStatus
    /** Libéré pour économiser la mémoire ; rechargé automatiquement à la prochaine utilisation. */
    data class Sleeping(val modelName: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus

    val available: Boolean get() = this is Ready || this is Sleeping
}

/**
 * Gestion partagée du LLM local : chargement paresseux, libération quand la mémoire manque,
 * et « propriétaire » de la conversation (Menu ou Courses ont chacun leurs outils).
 *
 * Le moteur ne tient qu'une conversation. Quand une autre tâche la ferme (generateOnce de la file d'étapes,
 * libération mémoire, autre propriétaire), elle est « suspendue » : on garde son prompt système, ses outils
 * et sa dernière réponse, et le prochain [send] de son propriétaire la rouvre avec un report compact.
 */
class ModelController(private val llm: LlmEngine, private val settings: SettingsStore) {

    enum class Kind { MENU, COURSES }

    /** Conversation tenue par l'appli : de quoi la rouvrir et estimer sa taille. */
    private data class Live(
        val id: String,
        val system: String,
        val tools: List<AgentTool>,
        val transcript: Transcript = Transcript(),
        val lastAnswer: String? = null,
    )

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Absent)
    val status: StateFlow<ModelStatus> = _status
    private val mutex = Mutex()
    private val inUse = AtomicInteger(0)
    @Volatile private var owner: String? = null
    @Volatile private var loadedPath: String? = null

    /** Conversation du propriétaire actuel ([owner]). */
    @Volatile private var live: Live? = null

    /** Conversations fermées par une autre tâche, à rouvrir au prochain message de leur propriétaire. */
    private val suspended = ConcurrentHashMap<String, Live>()

    /**
     * Le moteur ne fait qu'une chose à la fois : une génération ne doit jamais fermer la conversation
     * d'une autre en cours (cause de plantages). Toutes les générations passent par ce verrou, en file.
     */
    private val generation = Mutex()

    /** Une discussion Courses est en cours : la file d'écriture des étapes attend qu'elle se termine. */
    val chatForeground = MutableStateFlow(false)

    fun setChatForeground(active: Boolean) { chatForeground.value = active }

    private fun modelFile(): File? = settings.current.modelPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }

    /** Met à jour l'état affiché sans charger le modèle (au démarrage, après un changement de réglage). */
    fun refreshAvailability() {
        val file = modelFile()
        _status.value = when {
            file == null -> ModelStatus.Absent
            llm.isLoaded() && loadedPath == file.absolutePath -> _status.value.takeIf { it is ModelStatus.Ready } ?: ModelStatus.Sleeping(file.nameWithoutExtension)
            _status.value is ModelStatus.Failed || _status.value is ModelStatus.Loading -> _status.value
            else -> ModelStatus.Sleeping(file.nameWithoutExtension)
        }
    }

    /** Charge le modèle si besoin. Renvoie false s'il n'est pas installé ou n'a pas pu démarrer. */
    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        val file = modelFile() ?: run { _status.value = ModelStatus.Absent; return false }
        if (llm.isLoaded() && loadedPath == file.absolutePath) return true
        _status.value = ModelStatus.Loading
        return try {
            val backend = llm.load(file.absolutePath, settings.current.preferGpu, maxTokensFor(file))
            loadedPath = file.absolutePath
            suspendOwner()
            _status.value = ModelStatus.Ready(backend, file.nameWithoutExtension)
            true
        } catch (e: Exception) {
            _status.value = ModelStatus.Failed(e.message ?: e.toString())
            false
        }
    }

    /** Libère la mémoire si le modèle ne sert pas (appelé par onTrimMemory). */
    fun releaseIfIdle(): Boolean {
        if (inUse.get() > 0 || generation.isLocked || !llm.isLoaded()) return false
        suspendOwner()
        llm.close()
        loadedPath = null
        modelFile()?.let { _status.value = ModelStatus.Sleeping(it.nameWithoutExtension) }
        return true
    }

    /** Met de côté la conversation du propriétaire actuel (avant qu'elle soit fermée) ; plus de propriétaire. */
    private fun suspendOwner() {
        val id = owner
        val current = live
        if (id != null && current != null && current.id == id) suspended[id] = current
        owner = null
        live = null
    }

    /** Taille de contexte effectivement chargée par le moteur. */
    fun maxTokens(): Int = llm.maxTokens()

    fun budget(kind: Kind): ContextBudget = when (kind) {
        Kind.MENU -> ContextBudget.menu(maxTokens())
        Kind.COURSES -> ContextBudget.courses(maxTokens())
    }

    fun owns(id: String) = owner == id && llm.hasConversation()

    /** [id] a une conversation en cours ou suspendue, que [send] saura poursuivre ou rouvrir. */
    fun hasConversation(id: String) = owns(id) || suspended.containsKey(id)

    /** Transcription de la conversation de [id] (en cours ou suspendue), null s'il n'en a pas. */
    fun transcript(id: String): Transcript? = current(id)?.transcript ?: suspended[id]?.transcript

    private fun current(id: String): Live? = live?.takeIf { it.id == id && owner == id }

    /** Tokens occupés dans la conversation en cours, selon le moteur (null si inconnu). */
    fun tokenCount(): Int? = llm.tokenCount()

    /** Interrompt la génération en cours. */
    fun cancel() = llm.cancel()

    companion object {
        /**
         * Mémoire de la conversation pleine (« Prefill input length exceeds available state entries ») : il faut
         * repartir d'une conversation neuve. Les messages renvoient déjà l'état utile (menu, panier).
         * Seul détecteur de l'appli.
         */
        fun isContextFull(t: Throwable): Boolean = generateSequence(t) { it.cause }.any { e ->
            val m = e.message.orEmpty()
            "exceeds available state entries" in m || "Prefill input length" in m ||
                (m.contains("context", true) && (m.contains("exceed", true) || m.contains("too long", true)))
        }

        /** Taille de contexte demandée selon le modèle : 6 144 pour E4B, 8 192 pour E2B, sinon 4 096. */
        fun maxTokensFor(modelFile: File): Int {
            val name = modelFile.name
            return when {
                name.contains("E4B", ignoreCase = true) -> 6_144
                name.contains("E2B", ignoreCase = true) -> 8_192
                else -> 4_096
            }
        }

        /** Report compact injecté en tête : chaque paire USER/ASSISTANT compte comme un tour de la transcription. */
        private fun turnsOf(initial: List<Msg>): Transcript {
            val turns = mutableListOf<Transcript.Turn>()
            var pendingUser: String? = null
            for (m in initial) {
                when (m.role) {
                    MsgRole.USER -> {
                        pendingUser?.let { turns += Transcript.Turn(it, "") }
                        pendingUser = m.text
                    }
                    MsgRole.ASSISTANT -> {
                        turns += Transcript.Turn(pendingUser.orEmpty(), m.text)
                        pendingUser = null
                    }
                }
            }
            pendingUser?.let { turns += Transcript.Turn(it, "") }
            return Transcript(turns)
        }

        /** Tokens d'un résultat d'outil : sa taille en caractères (JSON) / 2,8. */
        private fun toolTokens(size: Int): Int =
            if (size <= 0) 0 else ceil(size / TokenEstimate.CHARS_PER_TOKEN_JSON).toInt()
    }

    suspend fun startConversation(
        id: String,
        systemPrompt: String,
        tools: List<AgentTool>,
        initial: List<Msg> = emptyList(),
        prefill: Boolean = false,
        maxOutputTokens: Int? = null,
    ) {
        check(ensureLoaded()) { "Assistant indisponible" }
        generation.withLock {
            // Un autre propriétaire perd sa place : on le garde de côté pour pouvoir le rouvrir.
            if (owner != id) suspendOwner()
            llm.newConversation(systemPrompt, tools, initial, prefill, maxOutputTokens)
            suspended.remove(id)
            live = Live(id, systemPrompt, tools, transcript = turnsOf(initial))
            owner = id
        }
    }

    /** Rouvre la conversation [saved] avec un report compact de sa dernière réponse. Appelé sous [generation]. */
    private fun restore(saved: Live) {
        val initial = CarryOver.chat(saved.lastAnswer)
        if (owner != saved.id) suspendOwner()
        llm.newConversation(saved.system, saved.tools, initial = initial)
        suspended.remove(saved.id)
        live = Live(saved.id, saved.system, saved.tools, transcript = turnsOf(initial), lastAnswer = saved.lastAnswer)
        owner = saved.id
    }

    /**
     * Message dans la conversation de [id] ; le modèle reste réservé pendant la génération.
     * Si la conversation a été fermée entre-temps (file d'étapes, mémoire libérée), elle est d'abord rouverte.
     * Le message, la réponse et la taille des résultats d'outils s'ajoutent à la transcription de [id].
     */
    fun send(id: String, message: String): Flow<ChatEvent> = flow {
        generation.withLock {
            if (!owns(id)) {
                val saved = current(id) ?: suspended[id]
                    ?: throw IllegalStateException("Aucune conversation pour $id")
                check(ensureLoaded()) { "Assistant indisponible" }
                restore(saved)
            }
            val answer = StringBuilder()
            var toolTotal = 0
            try {
                llm.send(message).collect { ev ->
                    when (ev) {
                        is ChatEvent.Token -> answer.append(ev.text)
                        is ChatEvent.ToolResult -> toolTotal += toolTokens(ev.size)
                        is ChatEvent.ToolCall -> Unit
                    }
                    emit(ev)
                }
            } finally {
                // Même interrompu ou en erreur, le tour a pu entrer dans l'historique du moteur : on le compte.
                val conv = current(id)
                if (conv != null) {
                    val text = answer.toString()
                    live = conv.copy(
                        transcript = conv.transcript.plus(Transcript.Turn(message, text, toolTotal)),
                        lastAnswer = text.takeIf { it.isNotBlank() } ?: conv.lastAnswer,
                    )
                }
            }
        }
    }

    /** Ancienne forme : message au propriétaire actuel. */
    @Deprecated("Préciser le propriétaire de la conversation", ReplaceWith("send(id, message)"))
    fun send(message: String): Flow<ChatEvent> =
        send(owner ?: throw IllegalStateException("Aucune conversation en cours"), message)

    suspend fun <T> use(block: suspend () -> T): T {
        inUse.incrementAndGet()
        try { return block() } finally { inUse.decrementAndGet() }
    }

    /** Génération ponctuelle sans outils ; la conversation en cours est suspendue (rouverte au prochain [send]). */
    suspend fun generateOnce(
        system: String,
        prompt: String,
        jsonSchema: String? = null,
        maxOutputTokens: Int? = null,
    ): String = use {
        check(ensureLoaded()) { "Assistant indisponible" }
        generation.withLock {
            suspendOwner() // avant que llm.generateOnce ne ferme la conversation
            llm.generateOnce(system, prompt, jsonSchema, maxOutputTokens)
        }
    }
}

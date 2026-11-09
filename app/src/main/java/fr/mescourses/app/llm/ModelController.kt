package fr.mescourses.app.llm

import fr.mescourses.app.data.SettingsStore
import fr.mescourses.core.agent.AgentTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
 */
class ModelController(private val llm: LlmEngine, private val settings: SettingsStore) {

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Absent)
    val status: StateFlow<ModelStatus> = _status
    private val mutex = Mutex()
    private val inUse = AtomicInteger(0)
    @Volatile private var owner: String? = null
    @Volatile private var loadedPath: String? = null

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
            val backend = llm.load(file.absolutePath, settings.current.preferGpu)
            loadedPath = file.absolutePath
            owner = null
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
        llm.close()
        owner = null
        loadedPath = null
        modelFile()?.let { _status.value = ModelStatus.Sleeping(it.nameWithoutExtension) }
        return true
    }

    fun owns(id: String) = owner == id && llm.hasConversation()

    companion object {
        /**
         * Mémoire de la conversation pleine (« Prefill input length exceeds available state entries ») : il faut
         * repartir d'une conversation neuve. Les messages renvoient déjà l'état utile (menu, panier).
         */
        fun isContextFull(t: Throwable): Boolean = generateSequence(t) { it.cause }.any { e ->
            val m = e.message.orEmpty()
            "exceeds available state entries" in m || "Prefill input length" in m ||
                (m.contains("context", true) && (m.contains("exceed", true) || m.contains("too long", true)))
        }
    }

    suspend fun startConversation(id: String, systemPrompt: String, tools: List<AgentTool>) {
        check(ensureLoaded()) { "Assistant indisponible" }
        generation.withLock {
            llm.newConversation(systemPrompt, tools)
            owner = id
        }
    }

    /** Message dans la conversation en cours ; le modèle reste réservé pendant la génération. */
    /**
     * Le moteur ne fait qu'une chose à la fois : une génération ne doit jamais fermer la conversation
     * d'une autre en cours (cause de plantages). Toutes les générations passent par ce verrou, en file.
     */
    private val generation = Mutex()

    fun send(message: String): Flow<ChatEvent> = flow { generation.withLock { emitAll(llm.send(message)) } }

    suspend fun <T> use(block: suspend () -> T): T {
        inUse.incrementAndGet()
        try { return block() } finally { inUse.decrementAndGet() }
    }

    suspend fun generateOnce(system: String, prompt: String): String = use {
        check(ensureLoaded()) { "Assistant indisponible" }
        generation.withLock {
            owner = null // la conversation en cours est fermée par generateOnce
            llm.generateOnce(system, prompt)
        }
    }
}

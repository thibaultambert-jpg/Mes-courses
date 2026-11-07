package fr.mescourses.app.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import fr.mescourses.core.agent.AgentTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LLM local via LiteRT-LM : GPU Adreno (OpenCL) en priorité, repli CPU.
 * Les outils sont exécutés automatiquement par LiteRT-LM (automaticToolCalling),
 * et chaque appel est remonté à l'interface pour l'affichage.
 */
class LiteRtLmEngine(private val context: Context) : LlmEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** Événements d'outils émis pendant la génération en cours. */
    @Volatile private var toolEvents: Channel<ChatEvent>? = null

    override fun isLoaded() = engine != null

    override suspend fun load(modelPath: String, preferGpu: Boolean): String = withContext(Dispatchers.Default) {
        close()
        val candidates = buildList {
            if (preferGpu) add("GPU" to Backend.GPU())
            add("CPU" to Backend.CPU())
        }
        var lastError: Throwable? = null
        for ((label, backend) in candidates) {
            try {
                val e = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = context.cacheDir.path))
                e.initialize()
                engine = e
                return@withContext label
            } catch (t: Throwable) {
                Log.w(TAG, "Échec du chargement en $label", t)
                lastError = t
            }
        }
        throw IllegalStateException("Impossible de charger le modèle : ${lastError?.message}", lastError)
    }

    override fun newConversation(systemPrompt: String, tools: List<AgentTool>) {
        val e = engine ?: error("Modèle non chargé")
        conversation?.close()
        conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                tools = tools.map { tool(ToolAdapter(it)) },
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
            ),
        )
    }

    override fun send(message: String): Flow<ChatEvent> = channelFlow {
        val conv = conversation ?: error("Aucune conversation")
        val events = Channel<ChatEvent>(Channel.UNLIMITED)
        toolEvents = events
        val relay = launch { for (ev in events) send(ev) }
        try {
            conv.sendMessageAsync(message).collect { chunk -> send(ChatEvent.Token(chunk.toString())) }
        } finally {
            toolEvents = null
            events.close()
            relay.join()
        }
    }.flowOn(Dispatchers.Default)

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    /** Adapte un outil métier au format OpenAPI attendu par LiteRT-LM. */
    private inner class ToolAdapter(private val agentTool: AgentTool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = agentTool.openApiJson()

        override fun execute(paramsJsonString: String): String {
            toolEvents?.trySend(ChatEvent.ToolCall(agentTool.name, paramsJsonString))
            val args = runCatching { Json.parseToJsonElement(paramsJsonString).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
            // Appelé depuis le thread d'inférence (pas le thread UI) : on peut bloquer.
            val result = runBlocking(Dispatchers.IO) { agentTool.execute(args) }
            val obj = result as? JsonObject
            val ok = obj?.get("ok")?.jsonPrimitive?.content != "false"
            val summary = (obj?.get("message") ?: obj?.get("erreur"))?.jsonPrimitive?.content.orEmpty()
            toolEvents?.trySend(ChatEvent.ToolResult(agentTool.name, ok, summary))
            return result.toString()
        }
    }

    private companion object {
        const val TAG = "LiteRtLmEngine"
    }
}

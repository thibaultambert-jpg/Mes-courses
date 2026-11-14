package fr.mescourses.app.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.llm.Msg
import fr.mescourses.core.llm.MsgRole
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
    private var loadedMaxTokens: Int? = null

    /** Événements d'outils émis pendant la génération en cours. */
    @Volatile private var toolEvents: Channel<ChatEvent>? = null

    override fun isLoaded() = engine != null

    override suspend fun load(modelPath: String, preferGpu: Boolean, maxTokens: Int?): String = withContext(Dispatchers.Default) {
        close()
        val sizes = listOfNotNull(maxTokens, DEFAULT_MAX_TOKENS).distinct()
        val backends = buildList {
            if (preferGpu) add("GPU" to Backend.GPU())
            add("CPU" to Backend.CPU())
        }
        var lastError: Throwable? = null
        for (n in sizes) {
            for ((label, backend) in backends) {
                try {
                    val e = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            maxNumTokens = n,
                            cacheDir = context.cacheDir.path,
                        ),
                    )
                    e.initialize()
                    engine = e
                    loadedMaxTokens = n
                    Log.i(TAG, "Modèle chargé en $label avec maxNumTokens=$n")
                    return@withContext label
                } catch (t: Throwable) {
                    Log.w(TAG, "Échec du chargement en $label avec maxNumTokens=$n", t)
                    lastError = t
                }
            }
        }
        throw IllegalStateException("Impossible de charger le modèle : ${lastError?.message}", lastError)
    }

    override fun maxTokens(): Int = loadedMaxTokens ?: DEFAULT_MAX_TOKENS

    override fun newConversation(
        systemPrompt: String,
        tools: List<AgentTool>,
        initial: List<Msg>,
        prefill: Boolean,
        maxOutputTokens: Int?,
    ) {
        val e = engine ?: error("Modèle non chargé")
        conversation?.close()
        conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = initial.map {
                    when (it.role) {
                        MsgRole.USER -> Message.user(it.text)
                        MsgRole.ASSISTANT -> Message.model(it.text)
                    }
                },
                tools = tools.map { tool(ToolAdapter(it)) },
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                prefillPrefaceOnInit = prefill,
                maxOutputToken = maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
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

    override fun hasConversation() = conversation != null

    override fun tokenCount(): Int? = runCatching { conversation?.getTokenCount() }.getOrNull()

    override fun cancel() {
        runCatching { conversation?.cancelProcess() }
    }

    override suspend fun generateOnce(
        systemPrompt: String,
        prompt: String,
        jsonSchema: String?,
        maxOutputTokens: Int?,
    ): String = withContext(Dispatchers.Default) {
        val e = engine ?: error("Modèle non chargé")
        runCatching { conversation?.close() }
        conversation = null
        val temp = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.4),
                maxOutputToken = maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = jsonSchema != null,
            ),
        )
        try {
            val out = StringBuilder()
            // ResponseFormat.json(schéma) = Type.JSON_OBJECT contraint par le schéma (0.18.1 n'a pas de JSON_SCHEMA).
            val format = jsonSchema?.let { ResponseFormat.json(it) }
            temp.sendMessageAsync(prompt, responseFormat = format).collect { out.append(it.toString()) }
            out.toString()
        } finally {
            temp.close()
        }
    }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedMaxTokens = null
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
            val text = result.toString()
            toolEvents?.trySend(ChatEvent.ToolResult(agentTool.name, ok, summary, size = text.length))
            return text
        }
    }

    private companion object {
        const val TAG = "LiteRtLmEngine"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

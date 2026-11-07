package fr.mescourses.core.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Un serveur MCP distant (exposé en Streamable HTTP par l'hôte maison). */
data class McpServerConfig(
    val url: String,
    /** Jeton envoyé en `Authorization: Bearer …` (optionnel). */
    val token: String? = null,
)

data class RemoteTool(
    val name: String,
    val description: String,
    /** Schéma JSON des paramètres : { type, properties, required }. */
    val inputSchema: JsonObject,
) {
    val properties: JsonObject get() = inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required: List<String>
        get() = (inputSchema["required"] as? JsonArray)?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
}

data class McpCallResult(
    val text: String,
    val isError: Boolean,
    val structured: JsonObject? = null,
)

/** Abstraction minimale d'un client MCP (remplaçable par un faux dans les tests). */
interface McpToolClient {
    suspend fun listTools(): List<RemoteTool>
    suspend fun call(name: String, arguments: JsonObject): McpCallResult
    suspend fun close()
}

/** Client MCP basé sur le SDK officiel Kotlin (transport Streamable HTTP). */
class SdkMcpToolClient(
    private val http: HttpClient,
    private val config: McpServerConfig,
) : McpToolClient {

    private val mutex = Mutex()
    private var client: Client? = null

    private suspend fun connected(): Client = mutex.withLock {
        client ?: Client(clientInfo = Implementation(name = "mes-courses", version = "0.1.0")).also { c ->
            val transport = StreamableHttpClientTransport(
                client = http,
                url = config.url,
                requestBuilder = {
                    config.token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                },
            )
            c.connect(transport)
            client = c
        }
    }

    override suspend fun listTools(): List<RemoteTool> = connected().listTools().tools.map { tool ->
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", tool.inputSchema.properties ?: JsonObject(emptyMap()))
            put("required", JsonArray(tool.inputSchema.required.orEmpty().map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        RemoteTool(tool.name, tool.description.orEmpty(), schema)
    }

    override suspend fun call(name: String, arguments: JsonObject): McpCallResult {
        val result = connected().callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
        val text = result.content.joinToString("\n") { block ->
            (block as? TextContent)?.text ?: Json.encodeToString(
                io.modelcontextprotocol.kotlin.sdk.types.ContentBlock.serializer(), block,
            )
        }
        return McpCallResult(text = text, isError = result.isError == true, structured = result.structuredContent)
    }

    override suspend fun close() {
        mutex.withLock {
            runCatching { client?.close() }
            client = null
        }
    }
}

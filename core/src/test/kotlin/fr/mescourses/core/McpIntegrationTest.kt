package fr.mescourses.core

import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.cart.RetailerSession
import fr.mescourses.core.mcp.McpServerConfig
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.mcp.SdkMcpToolClient
import fr.mescourses.core.model.ShoppingItem
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vrai serveur MCP (SDK officiel, Streamable HTTP) qui imite un drive, et vrai client de l'app. */
class McpIntegrationTest {

    private val cart = mutableListOf<String>()

    private fun fakeDrive(): Server {
        val server = Server(
            Implementation(name = "faux-drive", version = "1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.addTool(
            name = "search_products",
            description = "Recherche",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                },
                required = listOf("query"),
            ),
        ) { req ->
            val q = req.arguments?.get("query")?.jsonPrimitive?.content.orEmpty().lowercase()
            val json = when {
                "lait" in q -> """{"products":[{"id":"P1","name":"Lait demi-écrémé 1L","price":1.05},{"id":"P2","name":"Lait entier 1L","price":1.2}]}"""
                "farine" in q -> """[{"id":"F9","name":"Biscuits","price":2.0},{"id":"F1","name":"Farine de blé T55 1kg","price":0.95}]"""
                else -> """{"products":[]}"""
            }
            CallToolResult(content = listOf(TextContent(json)))
        }
        server.addTool(
            name = "add_to_cart",
            description = "Ajout",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("product_id", buildJsonObject { put("type", "string") })
                    put("quantity", buildJsonObject { put("type", "integer") })
                },
                required = listOf("product_id"),
            ),
        ) { req ->
            cart += req.arguments!!["product_id"]!!.jsonPrimitive.content
            CallToolResult(content = listOf(TextContent("""{"ok":true}""")))
        }
        server.addTool(name = "get_cart", description = "Panier") { _ ->
            CallToolResult(content = listOf(TextContent(cart.joinToString())))
        }
        return server
    }

    @Test fun remplissage_du_panier_de_bout_en_bout() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = port) { mcpStreamableHttp { fakeDrive() } }
        engine.start(wait = false)
        val http = HttpClient(io.ktor.client.engine.cio.CIO) { install(SSE) }
        try {
            val client = SdkMcpToolClient(http, McpServerConfig("http://127.0.0.1:$port/mcp", token = "secret"))
            val session = RetailerSession.open(Retailer.CARREFOUR, client)
            assertEquals("search_products", session.mapping.searchTool)

            val items = listOf(
                ShoppingItem(name = "Lait", quantity = 1.0, unit = "l"),
                ShoppingItem(name = "Farine", quantity = 500.0, unit = "g"),
                ShoppingItem(name = "Truffe blanche"),
            )
            val lines = CartPlanner.propose(session, items, pauseMs = 0).toList()
            assertEquals(listOf(LineStatus.PROPOSE, LineStatus.PROPOSE, LineStatus.INTROUVABLE), lines.map { it.status })
            assertEquals("F1", lines[1].product?.id) // la farine passe devant les biscuits

            val filled = CartPlanner.fill(session, lines, pauseMs = 0).toList()
            assertEquals(2, filled.size)
            assertTrue(filled.all { it.second.status == LineStatus.AJOUTE })
            assertEquals(listOf("P1", "F1"), cart)
            assertEquals("P1, F1", session.cart())
            session.close()
        } finally {
            http.close()
            engine.stop(100, 500)
        }
    }
}

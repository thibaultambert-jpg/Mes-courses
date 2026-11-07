package fr.mescourses.core.cart

import fr.mescourses.core.mcp.McpCallResult
import fr.mescourses.core.mcp.McpToolClient
import fr.mescourses.core.mcp.Product
import fr.mescourses.core.mcp.ProductParser
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.mcp.RetailerToolMapping
import fr.mescourses.core.mcp.RetailerToolMatcher
import fr.mescourses.core.mcp.ToolOverrides
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Session ouverte sur le serveur MCP d'une enseigne. */
class RetailerSession private constructor(
    val retailer: Retailer,
    private val client: McpToolClient,
    val mapping: RetailerToolMapping,
) {
    companion object {
        suspend fun open(retailer: Retailer, client: McpToolClient, overrides: ToolOverrides = ToolOverrides()): RetailerSession {
            val tools = client.listTools()
            return RetailerSession(retailer, client, RetailerToolMatcher.match(tools, retailer, overrides))
        }
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        mapping.fixedArgs.forEach { (k, v) -> put(k, v) }
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }

    suspend fun search(query: String, limit: Int = 5): List<Product> {
        val result = client.call(mapping.searchTool, args(mapping.searchQueryArg to query))
        if (result.isError) error(result.text.take(300))
        return ProductParser.parse(result).filter { it.available }.take(limit)
    }

    suspend fun addToCart(product: Product, quantity: Int = 1): McpCallResult {
        val tool = mapping.addTool ?: error("Ce serveur MCP n'expose pas d'outil d'ajout au panier.")
        val productArg = mapping.addProductArg ?: error("Paramètre produit introuvable pour $tool")
        return client.call(tool, args(productArg to product.id, (mapping.addQuantityArg ?: "quantity") to quantity))
    }

    suspend fun cart(): String {
        val tool = mapping.cartTool ?: return "Ce serveur MCP n'expose pas la lecture du panier."
        return client.call(tool, args()).text
    }

    suspend fun close() = client.close()
}

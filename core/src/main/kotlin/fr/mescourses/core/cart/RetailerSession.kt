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

/** Retrouve la valeur d'une clé (insensible à la casse) n'importe où dans une réponse JSON. */
internal object ContextFinder {
    fun find(result: McpCallResult, key: String): String? {
        val roots = listOfNotNull(
            result.structured,
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(result.text) }.getOrNull(),
        )
        return roots.firstNotNullOfOrNull { find(it, key, 0) }
    }

    private fun find(e: kotlinx.serialization.json.JsonElement, key: String, depth: Int): String? {
        if (depth > 8) return null
        return when (e) {
            is JsonObject -> e.entries.firstOrNull { it.key.equals(key, true) && it.value is JsonPrimitive }
                ?.let { (it.value as JsonPrimitive).content }
                ?: e.values.firstNotNullOfOrNull { find(it, key, depth + 1) }
            is kotlinx.serialization.json.JsonArray -> e.firstNotNullOfOrNull { find(it, key, depth + 1) }
            else -> null
        }
    }
}

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

    /** Valeurs des paramètres de contexte (lues une fois dans le panier). */
    private var context: Map<String, String>? = null

    private suspend fun contextArgs(): Map<String, String> {
        if (mapping.addContextArgs.isEmpty()) return emptyMap()
        context?.let { return it }
        val cartText = mapping.cartTool?.let { client.call(it, args()) }
        val found = mapping.addContextArgs.mapNotNull { key ->
            cartText?.let { ContextFinder.find(it, key) }?.let { key to it }
        }.toMap()
        val missing = mapping.addContextArgs - found.keys
        if (missing.isNotEmpty()) {
            error(
                "Paramètres requis introuvables pour ${mapping.addTool} : ${missing.joinToString()}. " +
                    "Ouvre une fois ton panier sur le site (choix du magasin) ou renseigne-les dans les Réglages.",
            )
        }
        return found.also { context = it }
    }

    suspend fun addToCart(product: Product, quantity: Int = 1): McpCallResult {
        val tool = mapping.addTool ?: error("Ce serveur MCP n'expose pas d'outil d'ajout au panier.")
        val productArg = mapping.addProductArg ?: error("Paramètre produit introuvable pour $tool")
        val extra = contextArgs().map { (k, v) -> k to v }.toTypedArray()
        return client.call(
            tool,
            args(productArg to product.idFor(productArg), (mapping.addQuantityArg ?: "quantity") to quantity, *extra),
        )
    }

    suspend fun cart(): String {
        val tool = mapping.cartTool ?: return "Ce serveur MCP n'expose pas la lecture du panier."
        return client.call(tool, args()).text
    }

    suspend fun close() = client.close()
}

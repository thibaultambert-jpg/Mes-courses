package fr.mescourses.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Les MCP drive communautaires n'ont pas tous les mêmes noms d'outils ni de paramètres
 * (search_products / search_product, product_id / ean…). On les reconnaît automatiquement.
 */
data class RetailerToolMapping(
    val searchTool: String,
    val searchQueryArg: String,
    val addTool: String?,
    val addProductArg: String?,
    val addQuantityArg: String?,
    val cartTool: String?,
    /** Paramètres fixes à ajouter à chaque appel (ex. retailer = "leclerc" pour un MCP multi-enseignes). */
    val fixedArgs: Map<String, String> = emptyMap(),
)

/** Noms d'outils imposés depuis les Réglages si la détection automatique se trompe. */
data class ToolOverrides(
    val searchTool: String? = null,
    val addTool: String? = null,
    val cartTool: String? = null,
)

object RetailerToolMatcher {

    private val queryArgs = listOf("query", "q", "search", "keyword", "keywords", "term", "text", "search_term", "searchTerm")
    private val productArgs = listOf(
        "product_id", "productId", "ean", "gtin", "sku", "id", "product_ref", "productRef", "offer_id", "offerId",
        "product", "code", "reference", "ref",
    )
    private val quantityArgs = listOf("quantity", "qty", "quantite", "count", "amount")
    private val retailerArgs = listOf("retailer", "enseigne", "store_type", "brand", "shop", "provider")

    fun match(tools: List<RemoteTool>, retailer: Retailer, overrides: ToolOverrides = ToolOverrides()): RetailerToolMapping {
        val byName = tools.associateBy { it.name }
        fun n(t: RemoteTool) = t.name.lowercase()

        val search = overrides.searchTool?.let(byName::get)
            ?: tools.firstOrNull { n(it) in setOf("search_products", "search_product", "searchproducts", "product_search") }
            ?: tools.firstOrNull { "search" in n(it) && "product" in n(it) }
            ?: tools.firstOrNull { "search" in n(it) && "recipe" !in n(it) && "store" !in n(it) }
            ?: error("Aucun outil de recherche de produit trouvé parmi : ${tools.joinToString { it.name }}")

        val add = overrides.addTool?.let(byName::get)
            ?: tools.firstOrNull { n(it) in setOf("add_to_cart", "cart_add", "addtocart", "add_cart_item", "add_item_to_cart") }
            ?: tools.firstOrNull { "add" in n(it) && "cart" in n(it) }
            ?: tools.firstOrNull { ("set" in n(it) || "update" in n(it)) && "cart" in n(it) && "item" in n(it) }

        val cart = overrides.cartTool?.let(byName::get)
            ?: tools.firstOrNull { n(it) in setOf("get_cart", "view_cart", "cart", "get_basket") }
            ?: tools.firstOrNull { "cart" in n(it) && ("get" in n(it) || "view" in n(it) || "show" in n(it) || "list" in n(it)) }

        val fixed = LinkedHashMap<String, String>()
        for (tool in listOfNotNull(search, add, cart)) {
            for ((prop, schema) in tool.properties) {
                if (prop.lowercase() in retailerArgs) {
                    val enumValues = ((schema as? JsonObject)?.get("enum") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                    val value = enumValues.firstOrNull { it.equals(retailer.mcpKey, true) }
                        ?: enumValues.firstOrNull { it.contains(retailer.mcpKey, true) }
                        ?: retailer.mcpKey
                    fixed[prop] = value
                }
            }
        }

        return RetailerToolMapping(
            searchTool = search.name,
            searchQueryArg = pickArg(search, queryArgs, exclude = fixed.keys) ?: "query",
            addTool = add?.name,
            addProductArg = add?.let { pickArg(it, productArgs, exclude = fixed.keys + quantityArgs) },
            addQuantityArg = add?.let { t -> quantityArgs.firstOrNull { it in t.properties } },
            cartTool = cart?.name,
            fixedArgs = fixed,
        )
    }

    /** Premier paramètre connu, sinon premier paramètre obligatoire de type chaîne. */
    private fun pickArg(tool: RemoteTool, preferred: List<String>, exclude: Collection<String>): String? {
        val props = tool.properties.keys.filter { it !in exclude }
        preferred.firstOrNull { it in props }?.let { return it }
        preferred.firstOrNull { p -> props.any { it.equals(p, ignoreCase = true) } }
            ?.let { p -> return props.first { it.equals(p, ignoreCase = true) } }
        return tool.required.firstOrNull { it in props && isString(tool, it) }
            ?: props.firstOrNull { isString(tool, it) }
    }

    private fun isString(tool: RemoteTool, prop: String): Boolean {
        val type = ((tool.properties[prop] as? JsonObject)?.get("type") as? JsonPrimitive)?.content
        return type == null || type == "string"
    }
}

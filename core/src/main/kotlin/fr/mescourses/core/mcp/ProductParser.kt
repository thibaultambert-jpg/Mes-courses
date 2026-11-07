package fr.mescourses.core.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class Product(
    val id: String,
    val name: String,
    val brand: String? = null,
    val price: Double? = null,
    /** Prix au kilo / litre, tel que fourni par l'enseigne (ex. « 2,35 €/kg »). */
    val unitPrice: String? = null,
    val packaging: String? = null,
    val available: Boolean = true,
    /** Tous les identifiants trouvés (id, ean, sku…) : l'outil d'ajout peut en attendre un en particulier. */
    val identifiers: Map<String, String> = emptyMap(),
) {
    fun idFor(param: String?): String = param?.let { p -> identifiers.entries.firstOrNull { it.key.equals(p, true) }?.value } ?: id
}

/**
 * Lit la réponse d'un outil de recherche MCP, quel que soit son format JSON :
 * tableau à la racine, { products: [...] }, { results: { items: [...] } }…
 */
object ProductParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val idKeys = listOf("id", "product_id", "productId", "ean", "gtin", "sku", "code", "ref", "reference", "offerId", "offer_id")
    private val nameKeys = listOf("name", "title", "label", "libelle", "designation", "productName", "product_name", "nom")
    private val brandKeys = listOf("brand", "marque", "brandName")
    private val priceKeys = listOf("price_eur", "priceEur", "price", "prix", "amount", "current_price", "currentPrice", "finalPrice", "sellPrice")
    private val unitPriceKeys = listOf("unit_price", "unitPrice", "price_per_unit", "pricePerUnit", "prixAuKilo", "perUnitLabel", "unitPriceLabel")
    private val packagingKeys = listOf("packaging", "format", "conditionnement", "quantity_label", "size", "weight")
    private val availabilityKeys = listOf("available", "in_stock", "inStock", "isAvailable", "disponible")

    fun parse(result: McpCallResult): List<Product> {
        val elements = buildList {
            result.structured?.let { add(it) }
            runCatching { json.parseToJsonElement(result.text.trim()) }.getOrNull()?.let { add(it) }
            // Certains serveurs mettent un JSON après une phrase d'introduction.
            if (isEmpty()) {
                val start = result.text.indexOfFirst { it == '[' || it == '{' }
                if (start >= 0) runCatching { json.parseToJsonElement(result.text.substring(start)) }.getOrNull()?.let { add(it) }
            }
        }
        return elements.asSequence()
            .flatMap { collect(it).asSequence() }
            .mapNotNull(::toProduct)
            .distinctBy { it.id }
            .toList()
    }

    private fun collect(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap { collect(it) }
        is JsonObject -> {
            val flat = flatten(element)
            if (looksLikeProduct(flat)) listOf(flat) else element.values.flatMap { collect(it) }
        }
        else -> emptyList()
    }

    /** Format JSON:API ({ id, attributes: { title, ean… } }) : on remonte les attributs d'un niveau. */
    private fun flatten(obj: JsonObject): JsonObject {
        val nested = listOf("attributes", "product", "item").mapNotNull { obj[it] as? JsonObject }
        if (nested.isEmpty()) return obj
        val merged = LinkedHashMap<String, JsonElement>()
        nested.forEach { merged.putAll(it) }
        merged.putAll(obj.filterKeys { it !in setOf("attributes", "product", "item") })
        return JsonObject(merged)
    }

    /** Cherche un prix en profondeur (ex. offers.{ean}.{offre}.attributes.price.price). */
    private fun deepPrice(element: JsonElement, depth: Int = 0): Double? {
        if (depth > 6) return null
        return when (element) {
            is JsonObject -> priceKeys.firstNotNullOfOrNull { k -> element[k]?.let { (it as? JsonPrimitive).number() ?: deepPrice(it, depth + 1) } }
                ?: element.values.firstNotNullOfOrNull { deepPrice(it, depth + 1) }
            is JsonArray -> element.firstNotNullOfOrNull { deepPrice(it, depth + 1) }
            else -> null
        }
    }

    private fun looksLikeProduct(obj: JsonObject) =
        idKeys.any { obj[it] is JsonPrimitive } && nameKeys.any { obj[it] is JsonPrimitive }

    private fun JsonObject.first(keys: List<String>): JsonElement? = keys.firstNotNullOfOrNull { this[it] }

    private fun JsonElement?.text(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> (this["name"] ?: this["label"] ?: this["value"] ?: this["formatted"]).text()
        else -> null
    }

    private fun JsonElement?.number(): Double? = when (this) {
        is JsonPrimitive -> doubleOrNull ?: contentOrNull
            ?.replace(Regex("[^0-9,.]"), "")?.replace(',', '.')?.toDoubleOrNull()
        is JsonObject -> (this["value"] ?: this["amount"] ?: this["price"]).number()
        else -> null
    }

    private fun toProduct(obj: JsonObject): Product? {
        val id = obj.first(idKeys).text() ?: return null
        val name = obj.first(nameKeys).text() ?: return null
        val available = (obj.first(availabilityKeys) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
        return Product(
            id = id,
            name = name,
            brand = obj.first(brandKeys).text(),
            price = obj.first(priceKeys).number() ?: deepPrice(obj),
            unitPrice = obj.first(unitPriceKeys).text(),
            packaging = obj.first(packagingKeys).text(),
            available = available,
            identifiers = idKeys.mapNotNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
        )
    }
}

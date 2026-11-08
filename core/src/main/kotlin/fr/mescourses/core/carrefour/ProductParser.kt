package fr.mescourses.core.carrefour

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Un produit du drive. `id` vaut l'EAN quand il est connu (c'est lui qu'attend le panier Carrefour). */
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
    val nutriscore: String? = null,
    val promo: String? = null,
    val imageUrl: String? = null,
    /** Tous les identifiants trouvés (id, ean, sku…). */
    val identifiers: Map<String, String> = emptyMap(),
    /**
     * Service du drive qui propose l'offre (ex. « 0678-150-7052 ») : c'est le `basketServiceId` exigé pour
     * ajouter au panier. Lu dans `offers.{ean}.{offerServiceId}` des résultats de recherche.
     */
    val offerServiceId: String? = null,
    val description: String? = null,
) {
    val ean: String get() = identifiers["ean"] ?: identifiers["gtin13"] ?: identifiers["gtin"] ?: id
    val organic: Boolean get() = Regex("""\bbio\b""", RegexOption.IGNORE_CASE).containsMatchIn(name + " " + (brand ?: ""))

    fun idFor(param: String?): String = param?.let { p -> identifiers.entries.firstOrNull { it.key.equals(p, true) }?.value } ?: id
}

/**
 * Lit des produits dans n'importe quelle réponse JSON de Carrefour : JSON:API (`{type, id, attributes:{…}}`),
 * tableau à la racine, objets imbriqués… Les attributs sont « aplatis » d'un niveau et le prix est cherché
 * en profondeur (`offers.{ean}.{offre}.attributes.price.price`).
 */
object ProductParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val idKeys = listOf("ean", "gtin13", "gtin", "id", "product_id", "productId", "sku", "code", "ref", "reference", "offerId", "offer_id")
    private val nameKeys = listOf("title", "name", "label", "libelle", "designation", "productName", "product_name", "nom")
    private val brandKeys = listOf("brand", "marque", "brandName")
    private val priceKeys = listOf("price_eur", "priceEur", "price", "prix", "amount", "current_price", "currentPrice", "finalPrice", "sellPrice")
    private val unitPriceKeys = listOf("perUnitLabel", "unit_price", "unitPrice", "price_per_unit", "pricePerUnit", "prixAuKilo", "unitPriceLabel")
    private val packagingKeys = listOf("packaging", "format", "conditionnement", "quantity_label", "size", "weight")
    private val availabilityKeys = listOf("available", "in_stock", "inStock", "isAvailable", "disponible")
    private val offerAvailabilityKeys = listOf("purchasable", "isPurchasable", "available", "isAvailable", "inStock")

    /** Analyse un texte (JSON ou JSON précédé d'une phrase). */
    fun parse(text: String, structured: JsonObject? = null): List<Product> {
        val elements = buildList {
            structured?.let { add(it) }
            runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()?.let { add(it) }
            if (isEmpty()) {
                val start = text.indexOfFirst { it == '[' || it == '{' }
                if (start >= 0) runCatching { json.parseToJsonElement(text.substring(start)) }.getOrNull()?.let { add(it) }
            }
        }
        return elements.flatMap { parse(it) }.distinctBy { it.id }
    }

    fun parse(element: JsonElement): List<Product> = collect(element).mapNotNull(::toProduct).distinctBy { it.id }

    private fun collect(element: JsonElement, depth: Int = 0): List<JsonObject> {
        if (depth > 40) return emptyList()
        return when (element) {
            is JsonArray -> element.flatMap { collect(it, depth + 1) }
            is JsonObject -> {
                val flat = flatten(element)
                if (looksLikeProduct(flat)) listOf(flat) else element.values.flatMap { collect(it, depth + 1) }
            }
            else -> emptyList()
        }
    }

    /** Format JSON:API : on remonte les attributs d'un niveau. */
    private fun flatten(obj: JsonObject): JsonObject {
        val nested = listOf("attributes", "product", "item").mapNotNull { obj[it] as? JsonObject }
        if (nested.isEmpty()) return obj
        val merged = LinkedHashMap<String, JsonElement>()
        nested.forEach { merged.putAll(it) }
        merged.putAll(obj.filterKeys { it !in setOf("attributes", "product", "item") })
        return JsonObject(merged)
    }

    private fun looksLikeProduct(obj: JsonObject): Boolean {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        if (type != null && type != "product") return false
        return idKeys.any { obj[it] is JsonPrimitive } && nameKeys.any { obj[it] is JsonPrimitive }
    }

    private fun JsonObject.first(keys: List<String>): JsonElement? = keys.firstNotNullOfOrNull { this[it] }

    private fun JsonElement?.text(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        is JsonObject -> (this["name"] ?: this["label"] ?: this["value"] ?: this["formatted"]).text()
        else -> null
    }

    private fun JsonElement?.number(): Double? = when (this) {
        is JsonPrimitive -> doubleOrNull ?: contentOrNull?.replace(Regex("[^0-9,.]"), "")?.replace(',', '.')?.toDoubleOrNull()
        is JsonObject -> (this["value"] ?: this["amount"] ?: this["price"]).number()
        else -> null
    }

    /** Cherche une valeur en profondeur (ex. prix dans offers.{ean}.{offre}.attributes.price.price). */
    private fun deep(element: JsonElement, keys: List<String>, pick: (JsonElement) -> Any?, depth: Int = 0): Any? {
        if (depth > 7) return null
        return when (element) {
            is JsonObject -> keys.firstNotNullOfOrNull { k -> element[k]?.let { pick(it) ?: deep(it, keys, pick, depth + 1) } }
                ?: element.values.firstNotNullOfOrNull { deep(it, keys, pick, depth + 1) }
            is JsonArray -> element.firstNotNullOfOrNull { deep(it, keys, pick, depth + 1) }
            else -> null
        }
    }

    private fun toProduct(obj: JsonObject): Product? {
        val ids = idKeys.mapNotNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        val id = ids["ean"] ?: ids["gtin13"] ?: ids.values.firstOrNull() ?: return null
        val name = obj.first(nameKeys).text() ?: return null
        val offers = obj["offers"]
        // Disponibilité : au niveau du produit, sinon dans l'offre du drive (offers…attributes.availability.purchasable).
        val available = (obj.first(availabilityKeys) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: offers?.let { deep(it, offerAvailabilityKeys, { e -> (e as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() }) as? Boolean }
            ?: true
        return Product(
            id = id,
            name = name,
            brand = obj.first(brandKeys).text(),
            price = obj.first(priceKeys).number() ?: offers?.let { deep(it, priceKeys, { e -> (e as? JsonPrimitive).number() }) as? Double },
            unitPrice = obj.first(unitPriceKeys).text()
                ?: offers?.let { deep(it, unitPriceKeys, { e -> (e as? JsonPrimitive)?.contentOrNull }) as? String },
            packaging = obj.first(packagingKeys).text(),
            available = available,
            nutriscore = (obj["nutriscore"] as? JsonObject)?.get("value").text() ?: (obj["nutriscore"] as? JsonPrimitive).text(),
            promo = (obj["promotion"] as? JsonObject)?.get("label").text(),
            imageUrl = imageOf(obj),
            identifiers = ids,
            offerServiceId = offerServiceId(offers),
            description = obj.first(listOf("description", "shortDescription", "marketingDescription")).text(),
        )
    }

    private val imageKeys = listOf("images", "image", "imageUrl", "image_url", "thumbnail", "picture", "media", "visual", "photos")
    private val imageFields = listOf("medium", "large", "url", "src", "path", "href", "main", "default", "small", "zoom", "original")
    private val imageExt = Regex("""\.(jpe?g|png|webp|gif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    /**
     * Photo du produit, quelle que soit la forme : `images[]` (objets ou chaînes), `image`, `imageUrl`, `media`…
     * Les adresses relatives (« /images/… », « //media… ») deviennent absolues.
     */
    private fun imageOf(obj: JsonObject): String? {
        fun pick(e: JsonElement?, depth: Int = 0): String? {
            if (e == null || depth > 4) return null
            return when (e) {
                is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() && (imageExt.containsMatchIn(it) || it.startsWith("http") || it.startsWith("/")) }
                is JsonArray -> e.firstNotNullOfOrNull { pick(it, depth + 1) }
                is JsonObject -> imageFields.firstNotNullOfOrNull { pick(e[it], depth + 1) }
                    ?: e.values.firstNotNullOfOrNull { v -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { imageExt.containsMatchIn(it) } }
                    ?: e.values.firstNotNullOfOrNull { v -> if (v !is JsonPrimitive) pick(v, depth + 1) else null }
            }
        }
        val raw = imageKeys.firstNotNullOfOrNull { pick(obj[it]) } ?: return null
        return absoluteImage(raw)
    }

    fun absoluteImage(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> CarrefourRoutes.ORIGIN + url
        else -> url
    }

    private val serviceIdPattern = Regex("""^\d+-\d+-\d+$""")

    fun isServiceId(value: String?) = value != null && serviceIdPattern.matches(value)

    /** `offers.{ean}.{offerServiceId}.attributes` : la clé du 2ᵉ niveau, ou un champ explicite de l'offre. */
    private fun offerServiceId(offers: JsonElement?): String? {
        val byEan = offers as? JsonObject ?: return null
        for (perEan in byEan.values) {
            val offersOfProduct = perEan as? JsonObject ?: continue
            for ((key, offer) in offersOfProduct) {
                val attrs = (offer as? JsonObject)?.let { (it["attributes"] as? JsonObject) ?: it }
                listOf("basketServiceId", "offerServiceId", "serviceId").firstNotNullOfOrNull { k ->
                    (attrs?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf(::isServiceId)
                }?.let { return it }
                if (isServiceId(key)) return key
            }
        }
        return null
    }
}

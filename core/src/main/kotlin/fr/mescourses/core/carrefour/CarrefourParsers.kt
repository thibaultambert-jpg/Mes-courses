package fr.mescourses.core.carrefour

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup

/**
 * Lecture des réponses carrefour.fr. Les formats exacts ne sont pas documentés : les lecteurs sont
 * volontairement tolérants (recherche des champs en profondeur, plusieurs noms possibles).
 * Formats et sources : docs/carrefour/ROUTES_CARREFOUR.md.
 */
object CarrefourParsers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseJson(text: String): JsonElement? = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()

    // ---------- Recherche ----------

    /** Réponse de /s : JSON:API si les en-têtes JSON ont été acceptés, sinon page HTML (état Nuxt). */
    fun parseSearch(body: String): List<Product> {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ProductParser.parse(body)
        val nuxt = extractNuxtData(body) ?: return emptyList()
        return ProductParser.parse(nuxt)
    }

    /**
     * Extrait l'état embarqué dans une page HTML Nuxt :
     * - `window.__INITIAL_STATE__ = {…, "routeData": "[…]"}` (format décrit par mcp-carrefour)
     * - `<script id="__NUXT_DATA__" type="application/json">[…]</script>` (Nuxt 3)
     * et résout le tableau indexé (chaque entier renvoie à une autre case du tableau).
     */
    fun extractNuxtData(html: String): JsonElement? {
        Regex("""(?:window\.)?(?:ONECF_)?__INITIAL_STATE__\s*=\s*(\{.+?\})\s*;?\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { raw ->
                val state = parseJson(raw) as? JsonObject
                val routeData = (state?.get("routeData") as? JsonPrimitive)?.contentOrNull?.let { parseJson(it) }
                    ?: state?.get("routeData")
                if (routeData is JsonArray) return resolveIndexed(routeData)
                if (state != null) return state
            }
        val doc = Jsoup.parse(html)
        doc.selectFirst("script#__NUXT_DATA__")?.data()?.let { parseJson(it) }?.let { if (it is JsonArray) return resolveIndexed(it) }
        return null
    }

    /** Résout un tableau « devalue » de Nuxt : les valeurs des objets/tableaux sont des index. */
    fun resolveIndexed(rd: JsonArray): JsonElement = IndexedResolver(rd).resolve(0, 0)

    private class IndexedResolver(private val rd: JsonArray) {
        private val memo = HashMap<Int, JsonElement>()

        /** Une référence est un entier ; le reste est une valeur littérale. */
        private fun ref(e: JsonElement, depth: Int): JsonElement =
            (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { resolve(it, depth + 1) } ?: e

        fun resolve(index: Int, depth: Int): JsonElement {
            if (index !in rd.indices || depth > 60) return JsonNull
            memo[index]?.let { return it }
            val result = when (val v = rd[index]) {
                is JsonObject -> JsonObject(v.mapValues { (_, r) -> ref(r, depth) })
                is JsonArray -> {
                    val head = (v.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (head in REACTIVE_MARKERS && v.size >= 2) ref(v[1], depth) else JsonArray(v.map { ref(it, depth) })
                }
                else -> v
            }
            memo[index] = result
            return result
        }
    }

    private val REACTIVE_MARKERS = setOf("Reactive", "ShallowReactive", "Ref", "ShallowRef", "NuxtError", "Set", "Map")

    // ---------- Panier ----------

    private val quantityKeys = listOf("counter", "quantity", "qty", "quantite")
    private val totalKeys = listOf("totalAmount", "total", "totalPrice", "amount", "totalTTC")

    fun parseCart(body: String): Cart {
        val root = parseJson(body) ?: return Cart(emptyList(), null, null)
        val lines = LinkedHashMap<String, CartLineItem>()
        collectCartLines(root, lines, 0)
        return Cart(lines.values.toList(), findTotal(root), findContext(root))
    }

    private fun collectCartLines(e: JsonElement, out: MutableMap<String, CartLineItem>, depth: Int) {
        if (depth > 30) return
        when (e) {
            is JsonArray -> e.forEach { collectCartLines(it, out, depth + 1) }
            is JsonObject -> {
                val qty = quantityKeys.firstNotNullOfOrNull { k -> (e[k] as? JsonPrimitive)?.intOrNull }
                val product = ProductParser.parse(e).firstOrNull()
                if (qty != null && product != null && product.ean.length >= 8) {
                    val subtotal = listOf("totalItemPrice", "subtotal", "lineTotal", "totalPrice").firstNotNullOfOrNull { k -> num(e[k]) }
                    out.putIfAbsent(product.ean, CartLineItem(product.ean, product.name, qty, subtotal))
                } else {
                    e.values.forEach { collectCartLines(it, out, depth + 1) }
                }
            }
            else -> Unit
        }
    }

    private fun num(e: JsonElement?): Double? = when (e) {
        is JsonPrimitive -> e.doubleOrNull ?: e.contentOrNull?.replace(Regex("[^0-9,.]"), "")?.replace(',', '.')?.toDoubleOrNull()
        is JsonObject -> num(e["value"] ?: e["amount"] ?: e["price"])
        else -> null
    }

    private fun findTotal(root: JsonElement): Double? {
        fun search(e: JsonElement, depth: Int): Double? = when {
            depth > 6 -> null
            e is JsonObject -> totalKeys.firstNotNullOfOrNull { k -> num(e[k]) }
                ?: listOf("totals", "summary", "cart", "data", "attributes").firstNotNullOfOrNull { k -> e[k]?.let { search(it, depth + 1) } }
            else -> null
        }
        return search(root, 0)
    }

    /** Valeur d'une clé (insensible à la casse) n'importe où dans le JSON. */
    fun findValue(e: JsonElement, key: String, depth: Int = 0): String? {
        if (depth > 12) return null
        return when (e) {
            is JsonObject -> e.entries.firstOrNull { it.key.equals(key, true) && it.value is JsonPrimitive }
                ?.let { (it.value as JsonPrimitive).contentOrNull }
                ?: e.values.firstNotNullOfOrNull { findValue(it, key, depth + 1) }
            is JsonArray -> e.firstNotNullOfOrNull { findValue(it, key, depth + 1) }
            else -> null
        }
    }

    fun findContext(root: JsonElement): DriveContext? {
        val basket = findValue(root, "basketServiceId") ?: return null
        return DriveContext(
            basketServiceId = basket,
            subBasketType = findValue(root, "subBasketType") ?: CarrefourRoutes.DEFAULT_SUB_BASKET,
            facilityServiceId = findValue(root, "facilityServiceId"),
        )
    }

    // ---------- Magasins ----------

    fun parseStores(body: String): List<Store> {
        val root = parseJson(body) ?: return emptyList()
        val out = LinkedHashMap<String, Store>()
        fun walk(e: JsonElement, depth: Int) {
            if (depth > 20) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    val attrs = (e["attributes"] as? JsonObject)?.let { JsonObject(it + e.filterKeys { k -> k != "attributes" }) } ?: e
                    val id = text(attrs["id"]) ?: text(attrs["ref"]) ?: text(attrs["storeId"])
                    val name = text(attrs["name"]) ?: text(attrs["title"]) ?: text(attrs["storeName"])
                    if (id != null && name != null && (attrs["address"] != null || attrs["distance"] != null || attrs["ref"] != null)) {
                        val address = attrs["address"]
                        out.putIfAbsent(
                            id,
                            Store(
                                id = id,
                                name = name,
                                address = (address as? JsonObject)?.let { a -> listOfNotNull(text(a["address1"]) ?: text(a["street"]) ?: text(a["line1"])).joinToString() }
                                    ?: text(address),
                                city = (address as? JsonObject)?.let { a -> text(a["city"]) } ?: text(attrs["city"]),
                                distanceKm = num(attrs["distance"]),
                                ref = text(attrs["ref"]) ?: text(attrs["reference"]),
                            ),
                        )
                    } else {
                        e.values.forEach { walk(it, depth + 1) }
                    }
                }
                else -> Unit
            }
        }
        walk(root, 0)
        return out.values.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
    }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    // ---------- Créneaux ----------

    fun parseTimeslots(body: String, max: Int = 5): List<TimeSlot> {
        val root = parseJson(body) ?: return emptyList()
        val out = mutableListOf<TimeSlot>()
        fun walk(e: JsonElement, day: String?, depth: Int) {
            if (depth > 20) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, day, depth + 1) }
                is JsonObject -> {
                    val a = (e["attributes"] as? JsonObject) ?: e
                    val begin = text(a["begDate"]) ?: text(a["begin"]) ?: text(a["start"]) ?: text(a["startDate"]) ?: text(a["from"])
                    val end = text(a["endDate"]) ?: text(a["end"]) ?: text(a["to"])
                    val currentDay = text(a["date"]) ?: text(a["day"]) ?: day
                    if (begin != null && end != null) {
                        val available = (a["available"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                            ?: (a["isAvailable"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                            ?: (a["status"] as? JsonPrimitive)?.contentOrNull?.let { !it.contains("unavailable", true) && !it.contains("full", true) }
                            ?: true
                        out += TimeSlot(
                            date = currentDay ?: begin.take(10),
                            start = hour(begin),
                            end = hour(end),
                            price = num(a["price"]) ?: num(a["fees"]),
                            available = available,
                        )
                    } else {
                        e.forEach { (_, v) -> walk(v, currentDay, depth + 1) }
                    }
                }
                else -> Unit
            }
        }
        walk(root, null, 0)
        return out.filter { it.available }.sortedWith(compareBy({ it.date }, { it.start })).take(max)
    }

    /** « 2026-11-09T18:30:00+01:00 » → « 18:30 ». */
    private fun hour(value: String): String = Regex("""T?(\d{2}:\d{2})""").find(value.substringAfter('T', value))?.groupValues?.get(1) ?: value

    // ---------- Détail produit ----------

    data class ProductDetail(
        val product: Product,
        val ingredients: String? = null,
        val allergens: String? = null,
    )

    /** Page produit HTML (JSON-LD) ou réponse JSON de /products. */
    fun parseProductDetail(body: String): ProductDetail? {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val root = parseJson(body) ?: return null
            val p = ProductParser.parse(root).firstOrNull() ?: return null
            return ProductDetail(
                p,
                ingredients = findValue(root, "ingredients") ?: findValue(root, "composition"),
                allergens = findValue(root, "allergens") ?: findValue(root, "allergenes"),
            )
        }
        val doc = Jsoup.parse(body)
        for (script in doc.select("script[type=application/ld+json]")) {
            val e = parseJson(script.data()) ?: continue
            val obj = (if (e is JsonArray) e.firstOrNull { (it as? JsonObject)?.get("@type")?.let(::text) == "Product" } else e) as? JsonObject ?: continue
            if (text(obj["@type"]) != "Product") continue
            val offers = obj["offers"].let { if (it is JsonArray) it.firstOrNull() else it } as? JsonObject
            val ean = text(obj["gtin13"]) ?: text(obj["gtin"]) ?: text(obj["sku"]) ?: continue
            val brand = (obj["brand"] as? JsonObject)?.let { text(it["name"]) } ?: text(obj["brand"])
            return ProductDetail(
                Product(
                    id = ean,
                    name = text(obj["name"]) ?: continue,
                    brand = brand,
                    price = num(offers?.get("price")),
                    available = text(offers?.get("availability"))?.endsWith("InStock") ?: true,
                    imageUrl = text(obj["image"]) ?: ((obj["image"] as? JsonArray)?.firstOrNull()?.let(::text)),
                    identifiers = mapOf("ean" to ean),
                ),
                ingredients = (extractNuxtData(body)?.let { findValue(it, "ingredients") }),
            )
        }
        return null
    }
}

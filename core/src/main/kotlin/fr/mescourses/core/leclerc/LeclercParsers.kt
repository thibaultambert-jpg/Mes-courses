package fr.mescourses.core.leclerc

import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.CartLineItem
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.Store
import fr.mescourses.core.retail.Scrape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Lecture des pages et réponses E.Leclerc Drive. Les pages (ASP.NET) passent les produits à des widgets sous
 * forme d'objets JSON contenant `iIdProduit` : on prend le plus petit objet qui entoure chaque occurrence.
 */
object LeclercParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    /** Tous les objets produit (avec `iIdProduit`) trouvés dans un texte HTML/JS. */
    fun productRecords(raw: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        Regex("\"iIdProduit\"\\s*:").findAll(raw).forEach { m ->
            val text = Scrape.enclosingObject(raw, m.range.first) ?: return@forEach
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let(out::add)
        }
        return out
    }

    fun product(o: JsonObject): Product {
        val id = o.str("iIdProduit") ?: ""
        val label = Scrape.decodeEntities(listOfNotNull(o.str("sLibelleLigne1"), o.str("sLibelleLigne2")).joinToString(" ").trim())
        val stock = o.num("iQteDisponible")
        return Product(
            id = id,
            name = label.ifBlank { "Produit $id" },
            price = o.num("nrPVUnitaireTTC") ?: Scrape.euro(o.str("sPrixUnitaire")),
            unitPrice = o.str("sPrixParUniteDeMesure"),
            available = stock == null || stock > 0,
            promo = o.str("sPrixPromo")?.let { "Promo : $it" },
            imageUrl = o.str("sUrlVignetteProduit"),
            identifiers = mapOf("iIdProduit" to id),
        )
    }

    /** Produits d'une page de recherche (sans doublons, seulement les fiches de type « Produit »). */
    fun parseSearch(html: String): List<Product> = productRecords(html)
        .filter { (it.str("sType") ?: "Produit") == "Produit" }
        .map(::product)
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }

    /** Panier embarqué dans chaque page de drive : tableau `lstProduits` + total `sTotalAPayer` près de `lstProduitsLight`. */
    fun parseCartPage(html: String): Cart {
        val arr = Scrape.arrayNamed(html, "lstProduits")
        val lines = if (arr == null) emptyList() else productRecords(arr).mapNotNull { o ->
            val qty = (o.num("iQuantitePanier") ?: o.num("iQtePanier") ?: 0.0).toInt()
            if (qty <= 0) return@mapNotNull null
            val p = product(o)
            CartLineItem(p.id, p.name, qty, o.num("rTotalAPayer") ?: Scrape.euro(o.str("sTotalAPayer")) ?: p.price?.let { it * qty })
        }.distinctBy { it.ean }
        val anchor = html.indexOf(LeclercRoutes.STORE_PAGE_MARKER)
        val scope = if (anchor >= 0) html.substring(anchor, minOf(html.length, anchor + 1500)) else ""
        val total = Regex("\"sTotalAPayer\"\\s*:\\s*\"([^\"]+)\"").find(scope)?.groupValues?.get(1)?.let(Scrape::euro)
            ?: lines.mapNotNull { it.subtotal }.takeIf { it.isNotEmpty() }?.sum()
        return Cart(lines, total, null)
    }

    /**
     * Réponse d'une écriture du panier : tableau d'événements. `Produit{id}` = état de la ligne,
     * `Panier{magasin}` = total du panier. Renvoie null si la réponse n'a pas cette forme.
     */
    fun parseCartEvents(body: String): Cart? {
        val events = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
        var total: Double? = null
        val lines = mutableListOf<CartLineItem>()
        for (e in events) {
            val o = e as? JsonObject ?: continue
            val id = o.str("sIdUnique") ?: continue
            val el = o["objElement"] as? JsonObject ?: continue
            when {
                id.startsWith("Panier") -> total = el.num("rTotalAPayer") ?: Scrape.euro(el.str("sTotalAPayer")) ?: total
                id.startsWith("Produit") && (el.str("sType") ?: "Produit") == "Produit" -> {
                    val qty = (el.num("iQuantitePanier") ?: 0.0).toInt()
                    val p = product(el)
                    if (qty > 0) lines += CartLineItem(p.id, p.name, qty, el.num("rTotalAPayer") ?: Scrape.euro(el.str("sTotalAPayer")))
                }
            }
        }
        return Cart(lines, total, null)
    }

    /** Est-ce une vraie page de drive ? Sinon : page anti-robot (DataDome) ou « session expirée ». */
    fun isStorePage(html: String) = html.contains(LeclercRoutes.STORE_PAGE_MARKER)
    fun isExpiredPage(html: String) = Regex("(?i)session a expir|sessionexpiree").containsMatchIn(html)

    /** Premier lieu proposé par l'autocomplétion : (id Woosmap, code postal, ville). */
    fun parsePlace(body: String): Triple<String, String?, String?>? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val first = (root["postalCodes"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return Triple(first.str("id") ?: return null, first.str("postalCode"), first.str("city"))
    }

    fun parseCoordinates(body: String): Pair<Double, Double>? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val c = root["coordinates"] as? JsonObject ?: root
        return (c.num("latitude") ?: return null) to (c.num("longitude") ?: return null)
    }

    /** Drives proches. L'hôte des courses (fdN-courses…) est rangé dans `Store.ref`. */
    fun parseStores(body: String, city: String?): List<Store> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val points: List<JsonElement> = ((root as? JsonObject)?.get("points") as? JsonArray) ?: (root as? JsonArray) ?: return emptyList()
        return points.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val id = p.str("noPL") ?: return@mapNotNull null
            val host = hostOf(p.str("urlSiteCourse") ?: p.str("urlBase")) ?: return@mapNotNull null
            val service = (p.str("serviceType") ?: p.str("type"))?.lowercase()
            Store(
                id = id,
                name = Scrape.decodeEntities(p.str("name") ?: "Drive $id") + if (service != null && service != "drive") " ($service)" else "",
                address = listOfNotNull(p.str("address"), p.str("postalCode")).joinToString(" ").ifBlank { null },
                city = p.str("city") ?: city,
                distanceKm = p.num("distance"),
                ref = "$host|${p.str("noPR") ?: id}",
            )
        }
    }

    private fun hostOf(url: String?): String? = url?.let { Regex("^https?://([^/]+)").find(it)?.groupValues?.get(1) }
}

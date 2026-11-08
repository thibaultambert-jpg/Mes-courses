package fr.mescourses.core.auchan

import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.CartLineItem
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.retail.Scrape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/** Ligne brute du panier Auchan (identifiants nécessaires aux écritures). */
data class AuchanCartLine(val lineId: String, val productId: String, val offerId: String?, val name: String?, val quantity: Int, val unitPrice: Double?)

data class AuchanCart(val id: String?, val store: String?, val lines: List<AuchanCartLine>) {
    fun toCart(names: Map<String, String>): Cart {
        val items = lines.map { l ->
            CartLineItem(l.productId, l.name ?: names[l.productId] ?: "Produit ${l.productId}", l.quantity, l.unitPrice?.let { Math.round(it * l.quantity * 100) / 100.0 })
        }
        val total = items.mapNotNull { it.subtotal }.takeIf { it.isNotEmpty() }?.sum()?.let { Math.round(it * 100) / 100.0 }
        return Cart(items, total, null)
    }
}

object AuchanParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Résultats d'une page de recherche (rendue côté serveur) : une fiche `article[data-current-offer-id]` par produit.
     * Les produits sponsorisés (liste « KAMINO ») sont placés après les résultats normaux.
     */
    fun parseSearch(html: String): List<Product> {
        val doc = Jsoup.parse(html, AuchanRoutes.ORIGIN)
        val all = doc.select("article[data-current-offer-id]").mapNotNull { a ->
            val id = a.attr("data-id").ifBlank { return@mapNotNull null }
            val offer = a.attr("data-current-offer-id").ifBlank { null }
            val qty = a.selectFirst(".quantity-selector[data-seller-id]")
            val seller = qty?.attr("data-seller-id")?.ifBlank { null }
            val stock = qty?.attr("data-stock")?.toIntOrNull()
            val title = a.selectFirst(".product-thumbnail__description")?.text()?.trim()?.ifBlank { null }
                ?: a.attr("data-product-name").ifBlank { null } ?: return@mapNotNull null
            val unitText = a.selectFirst("span[data-product-id]")?.text()?.trim()
            val promo = a.select("[class*=promo], [class*=flap], [class*=sticker], [class*=discount]")
                .map { it.text().trim() }.firstOrNull { it.isNotBlank() && it.length < 80 && !it.contains("cagnott", true) }
            val img: org.jsoup.nodes.Element? = a.selectFirst("img")
            val image: String? = img?.let { e -> val src: String = e.absUrl("src"); src.ifBlank { e.absUrl("data-src") } }?.ifBlank { null }
            val sponsored = a.attr("data-list").startsWith("KAMINO")
            sponsored to Product(
                id = id,
                name = title,
                brand = a.attr("data-product-brand").ifBlank { null },
                price = Scrape.euro(a.selectFirst(".product-price")?.text()),
                unitPrice = unitText?.ifBlank { null },
                available = stock == null || stock > 0,
                promo = promo,
                imageUrl = image,
                identifiers = buildMap {
                    put("productId", id)
                    offer?.let { put("offerId", it) }
                    seller?.let { put("sellerId", it) }
                },
            )
        }
        return (all.filterNot { it.first } + all.filter { it.first }).map { it.second }.distinctBy { it.id }
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    /** Panier de l'API (`carts/mine` ou réponse d'une écriture). Les prix sont en centimes. */
    fun parseCart(body: String): AuchanCart? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["items"] !is JsonArray && root.str("id") == null) return null
        var store: String? = null
        val lines = (root["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val it = e as? JsonObject ?: return@mapNotNull null
            val offering = it.obj("offering")
            store = store ?: offering?.obj("context")?.obj("seller")?.str("name")
            val cents = offering?.obj("prices")?.obj("price")?.num("amount")
            AuchanCartLine(
                lineId = it.str("id") ?: return@mapNotNull null,
                productId = it.str("productId") ?: return@mapNotNull null,
                offerId = it.str("offerId"),
                name = it.obj("product")?.str("name") ?: it.str("shortDescription"),
                quantity = (offering?.num("actualQuantity") ?: it.num("desiredQuantity") ?: 0.0).toInt(),
                unitPrice = cents?.let { c -> c / 100.0 },
            )
        }.filter { it.quantity > 0 }
        return AuchanCart(root.str("id"), store, lines)
    }
}

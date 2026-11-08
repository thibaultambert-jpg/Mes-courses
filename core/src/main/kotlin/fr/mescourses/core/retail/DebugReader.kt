package fr.mescourses.core.retail

import fr.mescourses.core.auchan.AuchanParsers
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.CarrefourParsers
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.leclerc.LeclercParsers

/**
 * Écran de debug : ce que l'application comprend d'une réponse brute (produits, panier), sans rien de personnel.
 * Sert à vérifier les lecteurs sur une vraie page quand une enseigne change son site.
 */
object DebugReader {

    fun summarize(retailer: Retailer, body: String): String = buildString {
        val trimmed = body.trimStart()
        val json = trimmed.startsWith("{") || trimmed.startsWith("[")
        appendLine("${body.length} caractères · ${if (json) "JSON" else "HTML / texte"}")
        when (retailer) {
            Retailer.LECLERC -> {
                if (!json) {
                    appendLine(
                        when {
                            LeclercParsers.isStorePage(body) -> "✅ Page de drive reconnue (marqueur lstProduitsLight)"
                            LeclercParsers.isExpiredPage(body) -> "⛔ Page « session expirée »"
                            else -> "⛔ Pas une page de drive : anti-robot (DataDome) ou autre page"
                        },
                    )
                    products(LeclercParsers.parseSearch(body))
                    cart(LeclercParsers.parseCartPage(body))
                } else {
                    LeclercParsers.parseCartEvents(body)?.let { cart(it) }
                    LeclercParsers.parseStores(body, null).takeIf { it.isNotEmpty() }?.let { s ->
                        appendLine("${s.size} drive(s) : " + s.take(3).joinToString(" ; ") { "${it.name} (${it.ref})" })
                    }
                }
            }
            Retailer.AUCHAN -> if (json) {
                AuchanParsers.parseCart(body)?.let { c ->
                    appendLine("Panier ${c.id ?: "?"} · magasin : ${c.store ?: "—"}")
                    cart(c.toCart(emptyMap()))
                } ?: appendLine("JSON non reconnu comme panier")
            } else {
                products(AuchanParsers.parseSearch(body))
            }
            Retailer.CARREFOUR -> {
                runCatching { CarrefourParsers.parseSearch(body) }.getOrNull()?.let { products(it) }
                runCatching { CarrefourParsers.parseCart(body) }.getOrNull()?.takeIf { it.lines.isNotEmpty() || it.total != null }?.let { cart(it) }
            }
        }
    }.trimEnd()

    private fun StringBuilder.products(list: List<Product>) {
        appendLine("${list.size} produit(s) lu(s), dont ${list.count { !it.available }} en rupture")
        list.take(3).forEach { p ->
            appendLine("• ${p.name} — ${p.price?.let { "%.2f €".format(it) } ?: "prix ?"} — ${if (p.available) "dispo" else "rupture"}")
            appendLine("  id ${p.id} · photo : ${p.imageUrl ?: "aucune"}")
        }
    }

    private fun StringBuilder.cart(c: Cart) {
        appendLine("Panier : ${c.lines.size} ligne(s), total ${c.total?.let { "%.2f €".format(it) } ?: "?"}")
        c.lines.take(3).forEach { l -> appendLine("• ${l.quantity} × ${l.name} (${l.ean})") }
    }
}

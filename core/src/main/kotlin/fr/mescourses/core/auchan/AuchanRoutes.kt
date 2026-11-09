package fr.mescourses.core.auchan

import java.net.URLEncoder

/**
 * TOUTES les routes auchan.fr utilisées par l'app (intégration non officielle, usage personnel).
 * Documentation et sources : docs/auchan/ROUTES_AUCHAN.md. Si le site change, c'est ici qu'il faut corriger.
 */
object AuchanRoutes {
    const val ORIGIN = "https://www.auchan.fr"
    const val API = "https://api.auchan.fr"

    /** Page panier du site : stationnement de la WebView (elle appelle l'API panier, ce qui donne les jetons) et finalisation. */
    const val CART_PAGE = "$ORIGIN/checkout/cart/"
    const val HOME = "$ORIGIN/"

    fun search(query: String) = "$ORIGIN/recherche?text=${enc(query)}"
    fun cartMine(consentId: String) = "$API/checkout/v1/carts/mine?consentId=${enc(consentId)}"
    /** Ajout depuis la recherche ; `desiredQuantity` = quantité ABSOLUE. */
    const val CART_UPDATE = "$ORIGIN/cart/update"
    /** Modification d'une ligne existante ; `desiredQuantity` = 0 la retire. */
    fun cartItems(cartId: String, consentId: String) = "$API/checkout/v1/carts/${enc(cartId)}/items?consentId=${enc(consentId)}"

    /** En-têtes que le site envoie à api.auchan.fr : relevés sur ses propres requêtes, puis rejoués. */
    val API_HEADERS_TO_REPLAY = listOf("authorization", "client-id", "x-gravitee-api-key")
    /** Le consentement figure dans l'URL des appels du site. */
    val CONSENT_REGEX = Regex("consentId=([0-9a-fA-F-]{36})")

    val HTML_HEADERS = mapOf("accept" to "text/html,application/xhtml+xml")
    val JSON_HEADERS = mapOf("accept" to "application/json")
    val JSON_BODY_HEADERS = JSON_HEADERS + ("content-type" to "application/json")

    /**
     * Pseudo-en-tête lu (et retiré) par le pont WebView : mode `credentials` du fetch (`include` par défaut).
     * Sert à réessayer sans cookies si l'API refuse les requêtes « avec identifiants » d'une autre origine.
     */
    const val CREDENTIALS_HEADER = "x-mescourses-credentials"

    /** Jamais appelées : paiement, validation, réservation de créneau. */
    val FORBIDDEN = listOf("/payment", "/checkout/v1/orders", "/slot", "/reservation")

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
}

/** Jetons de l'API panier, relevés sur les requêtes du site (jamais affichés ni journalisés). */
data class AuchanAuth(val headers: Map<String, String>, val consentId: String?) {
    val complete: Boolean get() = consentId != null && headers.containsKey("authorization")
}

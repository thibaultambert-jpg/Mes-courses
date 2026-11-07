package fr.mescourses.core.carrefour

import java.net.URLEncoder

/**
 * TOUTES les routes carrefour.fr utilisées par l'app (intégration non officielle, usage personnel).
 * Documentation et sources : docs/carrefour/ROUTES_CARREFOUR.md.
 * Si Carrefour change son site, c'est ici (et seulement ici) qu'il faut corriger.
 */
object CarrefourRoutes {
    const val ORIGIN = "https://www.carrefour.fr"
    const val IAM_ORIGIN = "https://moncompte.carrefour.fr"

    /** Page où « garer » la WebView invisible (requêtes same-origin). */
    const val PARKING = "$ORIGIN/"

    /** Connexion (ForgeRock) : se termine par /login/check?code=… puis retour sur www.carrefour.fr. */
    val AUTHORIZE_URL: String = "$IAM_ORIGIN/iam/oauth2/CarrefourConnect/authorize?" + listOf(
        "client_id" to "carrefour_onecarrefour_web",
        "redirect_uri" to "$ORIGIN/login/check",
        "response_type" to "code",
        "scope" to "openid iam",
    ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    /** Page panier du site, pour finaliser la commande (créneau, paiement) à la main. */
    const val WEB_CART = "$ORIGIN/mon-panier"

    // --- API (chemins relatifs à ORIGIN) ---
    fun search(query: String, page: Int = 1) = "/s?q=${enc(query)}&page=$page"
    const val CART = "/api/cart"
    const val CART_ITEMS = "/api/cart/items"
    const val PRODUCTS_BY_GTINS = "/products"
    fun timeslots(facilityServiceId: String) = "/api/timeslots?facilityServiceId=${enc(facilityServiceId)}"
    fun driveStores(lat: Double, lon: Double, postalCode: String, city: String, limit: Int = 20) =
        "/api/eligibility/drive?latitude=$lat&longitude=$lon&postalCode=${enc(postalCode)}&city=${enc(city)}&page=1&limit=$limit"
    const val FAVORITE_STORE = "/api/favoritestore"

    /** En-têtes attendus par les routes JSON (comme le site lui-même). */
    val JSON_HEADERS = mapOf(
        "accept" to "application/json, text/plain, */*",
        "x-requested-with" to "XMLHttpRequest",
    )
    val JSON_BODY_HEADERS = JSON_HEADERS + ("content-type" to "application/json")

    /** Routes qui ne doivent JAMAIS être appelées par l'app (paiement, validation, réservation). */
    val FORBIDDEN_PREFIXES = listOf("/api/checkout", "/api/cart/slot", "/api/cart/promo_code")

    /** Sous-panier par défaut du drive (« click & collect »). */
    const val DEFAULT_SUB_BASKET = "drive_clcv"

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
}

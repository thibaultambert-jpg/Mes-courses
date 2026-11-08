package fr.mescourses.core.auchan

import fr.mescourses.core.cart.RetailerBackend
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.HttpResponse
import fr.mescourses.core.carrefour.HttpTransport
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.retail.Retailer
import fr.mescourses.core.retail.RetailerHttp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Client Auchan Drive. Recherche : page HTML du site. Panier : API `api.auchan.fr`, avec les jetons
 * (authorization, client-id, x-gravitee-api-key, consentId) relevés sur les requêtes du site lui-même
 * dans la WebView garée sur la page panier. Tout passe par le [HttpTransport].
 */
class AuchanClient(
    transport: HttpTransport,
    /** Jetons relevés par la WebView (null tant qu'aucune requête du site n'a été vue). */
    private val auth: () -> AuchanAuth?,
    /** Recharge la page panier pour que le site rappelle son API (jetons frais). */
    private val refreshAuth: suspend () -> Unit = {},
    /** Magasin lu dans le panier (nom du vendeur), pour l'afficher dans les Réglages. */
    private val onStore: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    rateLimitWaitMs: Long = 3_000,
) : RetailerBackend {

    override val retailer = Retailer.AUCHAN
    private val who = retailer.label

    private val http = RetailerHttp(transport, who, AuchanRoutes.FORBIDDEN, rateLimitWaitMs, ::classify)

    private data class Cached<T>(val at: Long, val value: T)
    private val cacheTtlMs = 5 * 60 * 1000L
    private val searchCache = HashMap<String, Cached<List<Product>>>()
    private val lock = Mutex()
    /** Produits vus en recherche : l'API panier ne donne ni les noms ni l'offre à rejouer. */
    private val seen = LinkedHashMap<String, Product>()

    /** Mode `credentials` qui marche pour l'API (null = pas encore essayé). */
    @Volatile private var apiCredentials: String? = null

    private fun classify(r: HttpResponse): CarrefourException? = when {
        r.header("cf-mitigated") != null -> CarrefourException.ChallengeRequired(who)
        (r.status == 403 || r.status == 503) && (r.body.contains("captcha", true) || r.body.contains("datadome", true)) ->
            CarrefourException.ChallengeRequired(who)
        r.status == 403 -> CarrefourException.SessionExpired(who)
        else -> null
    }

    private suspend fun currentAuth(forceRefresh: Boolean = false): AuchanAuth {
        auth()?.takeIf { it.complete && !forceRefresh }?.let { return it }
        refreshAuth()
        return auth()?.takeIf { it.complete } ?: throw CarrefourException.SessionExpired(who)
    }

    /**
     * Appel à api.auchan.fr avec les jetons du site. Un 401/403 = jeton périmé : on recharge la page panier une fois.
     * Si le navigateur refuse l'appel « avec cookies » (CORS), on réessaie sans cookies (le jeton suffit).
     */
    private suspend fun api(method: String, url: (AuchanAuth) -> String, body: String? = null): HttpResponse {
        var a = currentAuth()
        repeat(2) { attempt ->
            val base = (if (body != null) AuchanRoutes.JSON_BODY_HEADERS else AuchanRoutes.JSON_HEADERS) + a.headers
            try {
                return withCredentials(method, url(a), base, body)
            } catch (e: CarrefourException.SessionExpired) {
                if (attempt == 1) throw e
                a = currentAuth(forceRefresh = true)
            }
        }
        throw CarrefourException.SessionExpired(who)
    }

    private suspend fun withCredentials(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val modes = apiCredentials?.let { listOf(it) } ?: listOf("include", "omit")
        var last: Exception? = null
        for (mode in modes) {
            try {
                return http.call(method, url, headers + (AuchanRoutes.CREDENTIALS_HEADER to mode), body).also { apiCredentials = mode }
            } catch (e: CarrefourException.Network) {
                last = e
            }
        }
        throw last ?: CarrefourException.Network("API panier injoignable", who)
    }

    private suspend fun rawCart(): AuchanCart {
        val body = api("GET", { AuchanRoutes.cartMine(it.consentId!!) }).body
        val cart = AuchanParsers.parseCart(body) ?: throw CarrefourException.Http(200, who)
        cart.store?.let(onStore)
        return cart
    }

    private suspend fun names(): Map<String, String> = lock.withLock { seen.mapValues { it.value.name } }

    override suspend fun sessionProbe(): Cart? = cart()

    override suspend fun cart(): Cart = rawCart().toCart(names())

    override suspend fun search(query: String, limit: Int): List<Product> =
        searchWithUnavailable(query, Int.MAX_VALUE).filter { it.available }.take(limit)

    /** Garde les produits en rupture (stock 0, `available = false`) pour que le panier les signale. */
    override suspend fun searchWithUnavailable(query: String, limit: Int): List<Product> {
        val key = query.trim().lowercase()
        lock.withLock { searchCache[key]?.takeIf { now() - it.at < cacheTtlMs }?.let { return it.value.take(limit) } }
        val html = http.call("GET", AuchanRoutes.search(query), AuchanRoutes.HTML_HEADERS).body
        val products = AuchanParsers.parseSearch(html)
        lock.withLock {
            searchCache[key] = Cached(now(), products)
            products.forEach { seen[it.id] = it }
            while (seen.size > 500) seen.remove(seen.keys.first())
        }
        return products.take(limit)
    }

    override suspend fun setQuantity(ean: String, quantity: Int): Cart {
        require(quantity in 0..50) { "Quantité invalide : $quantity" }
        val raw = rawCart()
        val line = raw.lines.firstOrNull { it.productId == ean }
        if (line != null) return updateLine(raw, line, quantity)
        if (quantity == 0) return raw.toCart(names())
        val product = lock.withLock { seen[ean] } ?: throw CarrefourException.NotFound("Ce produit (cherche-le d'abord)", who)
        return add(raw, product, quantity)
    }

    override suspend fun addToCart(product: Product, quantity: Int): Cart {
        val raw = rawCart()
        val line = raw.lines.firstOrNull { it.productId == product.id }
        val target = (line?.quantity ?: 0) + quantity
        require(target in 0..50) { "Quantité invalide : $target" }
        return if (line != null) updateLine(raw, line, target) else add(raw, withOffer(product), target)
    }

    /** Produit repris de la mémoire des choix (sans offre) : on retrouve son offre par une recherche de son nom. */
    private suspend fun withOffer(product: Product): Product {
        if (product.identifiers["offerId"] != null && product.identifiers["sellerId"] != null) return product
        lock.withLock { seen[product.id] }?.takeIf { it.identifiers["offerId"] != null }?.let { return it }
        return search(product.name, 30).firstOrNull { it.id == product.id }
            ?: throw CarrefourException.NotFound("« ${product.name} » (plus proposé par ton drive ?)", who)
    }

    private suspend fun add(raw: AuchanCart, product: Product, quantity: Int): Cart {
        val a = currentAuth()
        val offer = product.identifiers["offerId"] ?: throw CarrefourException.NotFound("L'offre de « ${product.name} »", who)
        val body = buildJsonObject {
            put("cartId", raw.id)
            putJsonArray("items") {
                add(buildJsonObject {
                    put("productId", product.id)
                    put("offerId", offer)
                    put("sellerType", "GROCERY")
                    put("desiredQuantity", quantity)
                    put("desiredType", "DEFAULT")
                    put("sellerId", product.identifiers["sellerId"])
                })
            }
            put("consentId", a.consentId)
            put("reservationId", JsonNull)
            put("mbaAvailabilityNeeded", true)
        }.toString()
        http.call("POST", AuchanRoutes.CART_UPDATE, AuchanRoutes.JSON_BODY_HEADERS, body)
        return cart()
    }

    private suspend fun updateLine(raw: AuchanCart, line: AuchanCartLine, quantity: Int): Cart {
        val cartId = raw.id ?: throw CarrefourException.Http(200, who)
        val body = buildJsonArray {
            add(buildJsonObject {
                put("id", line.lineId)
                put("productId", line.productId)
                put("offerId", line.offerId)
                put("desiredQuantity", quantity)
            })
        }.toString()
        val response = api("POST", { AuchanRoutes.cartItems(cartId, it.consentId!!) }, body)
        return AuchanParsers.parseCart(response.body)?.takeIf { it.id != null }?.also { c -> c.store?.let(onStore) }?.toCart(names()) ?: cart()
    }

    /** Les créneaux Auchan ne sont pas encore relevés : ils se choisissent sur le site. */
    override suspend fun timeslots(max: Int): List<TimeSlot> = emptyList()

    override fun forgetContext() {
        searchCache.clear()
    }
}

package fr.mescourses.core.carrefour

import fr.mescourses.core.cart.RetailerBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Client Carrefour typé. Ne fait AUCUNE requête lui-même : tout passe par le [HttpTransport]
 * (la WebView connectée dans l'app). Testable avec un faux transport.
 */
class CarrefourClient(
    private val transport: HttpTransport,
    /** Drive choisi dans l'app, utilisé si le panier ne révèle pas encore son contexte. */
    private val savedContext: () -> DriveContext? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val rateLimitWaitMs: Long = 3_000,
) : RetailerBackend {

    override val label = "Carrefour"

    private data class Cached<T>(val at: Long, val value: T)
    private val cacheTtlMs = 5 * 60 * 1000L
    private val searchCache = HashMap<String, Cached<List<Product>>>()
    private val detailCache = HashMap<String, Cached<CarrefourParsers.ProductDetail?>>()
    private val cacheLock = Mutex()

    @Volatile var lastContext: DriveContext? = null
        private set

    /** Requête avec traduction des erreurs ; un 429 est réessayé une fois après une pause. */
    suspend fun call(method: String, path: String, body: String? = null, json: Boolean = true): HttpResponse {
        if (CarrefourRoutes.FORBIDDEN_PREFIXES.any { path.startsWith(it) }) throw CarrefourException.Forbidden(path)
        val headers = if (!json) emptyMap() else if (body != null) CarrefourRoutes.JSON_BODY_HEADERS else CarrefourRoutes.JSON_HEADERS
        repeat(2) { attempt ->
            val r = try {
                transport.request(method, path, headers, body)
            } catch (e: CarrefourException) {
                throw e
            } catch (e: Exception) {
                throw CarrefourException.Network(e.message ?: e.toString())
            }
            when {
                isChallenge(r) -> throw CarrefourException.ChallengeRequired()
                r.status == 401 || r.status == 403 -> throw CarrefourException.SessionExpired()
                r.status == 429 && attempt == 0 -> delay(rateLimitWaitMs)
                r.status == 429 -> throw CarrefourException.RateLimited()
                r.status >= 400 -> throw CarrefourException.Http(r.status)
                else -> return r
            }
        }
        throw CarrefourException.RateLimited()
    }

    private fun isChallenge(r: HttpResponse): Boolean =
        r.header("cf-mitigated") != null ||
            (r.status == 403 || r.status == 503) && (r.body.contains("challenge-platform") || r.body.contains("cf-chl") || r.body.contains("Just a moment"))

    suspend fun isLoggedIn(): Boolean = try {
        cart(); true
    } catch (e: CarrefourException.SessionExpired) {
        false
    }

    // ---------- Recherche ----------

    override suspend fun search(query: String, limit: Int): List<Product> {
        val key = query.trim().lowercase()
        cacheLock.withLock { searchCache[key]?.takeIf { now() - it.at < cacheTtlMs }?.let { return it.value.take(limit) } }
        val products = CarrefourParsers.parseSearch(call("GET", CarrefourRoutes.search(query)).body).filter { it.available }
        cacheLock.withLock { searchCache[key] = Cached(now(), products) }
        return products.take(limit)
    }

    suspend fun productDetail(ean: String): CarrefourParsers.ProductDetail? {
        cacheLock.withLock { detailCache[ean]?.takeIf { now() - it.at < cacheTtlMs }?.let { return it.value } }
        val body = buildJsonObject {
            putJsonArray("gtins") { add(JsonPrimitive(ean)) }
            (lastContext ?: savedContext())?.facilityServiceId?.let { put("facilityServiceId", it) }
        }.toString()
        val detail = CarrefourParsers.parseProductDetail(call("POST", CarrefourRoutes.PRODUCTS_BY_GTINS, body).body)
        cacheLock.withLock { detailCache[ean] = Cached(now(), detail) }
        return detail
    }

    // ---------- Magasins ----------

    suspend fun driveStores(lat: Double, lon: Double, postalCode: String, city: String): List<Store> =
        CarrefourParsers.parseStores(call("GET", CarrefourRoutes.driveStores(lat, lon, postalCode, city)).body)

    // ---------- Panier ----------

    override suspend fun cart(): Cart {
        val cart = CarrefourParsers.parseCart(call("GET", CarrefourRoutes.CART).body)
        cart.context?.let { lastContext = it }
        return cart
    }

    private suspend fun context(): DriveContext =
        lastContext ?: cart().context ?: savedContext() ?: throw CarrefourException.NoDrive()

    override suspend fun setQuantity(ean: String, quantity: Int): Cart {
        require(quantity in 0..50) { "Quantité invalide : $quantity" }
        val ctx = context()
        val body = buildJsonObject {
            putJsonObject("trackingRequest") { put("pageType", "search"); put("pageId", "search") }
            put("items", JsonArray(listOf(buildJsonObject {
                put("basketServiceId", ctx.basketServiceId)
                put("counter", quantity)
                put("ean", ean)
                put("subBasketType", ctx.subBasketType)
            })))
        }
        val response = call("PATCH", CarrefourRoutes.CART, Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
        // La réponse contient le panier mis à jour ; à défaut on le relit.
        val parsed = CarrefourParsers.parseCart(response.body)
        return if (parsed.lines.isNotEmpty() || parsed.total != null) parsed.also { c -> c.context?.let { lastContext = it } } else cart()
    }

    override suspend fun addToCart(product: Product, quantity: Int): Cart {
        val current = cart().quantityOf(product.ean)
        return setQuantity(product.ean, current + quantity)
    }

    // ---------- Créneaux ----------

    override suspend fun timeslots(max: Int): List<TimeSlot> {
        val facility = context().facilityServiceId ?: savedContext()?.facilityServiceId ?: throw CarrefourException.NoDrive()
        return CarrefourParsers.parseTimeslots(call("GET", CarrefourRoutes.timeslots(facility)).body, max)
    }
}

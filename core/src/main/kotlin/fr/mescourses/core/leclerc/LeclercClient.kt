package fr.mescourses.core.leclerc

import fr.mescourses.core.cart.RetailerBackend
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.HttpResponse
import fr.mescourses.core.carrefour.HttpTransport
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.Store
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.retail.Retailer
import fr.mescourses.core.retail.RetailerHttp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Client E.Leclerc Drive. Comme pour Carrefour, aucune requête directe : tout passe par le [HttpTransport]
 * (WebView garée sur la page du drive, qui porte les cookies et passe DataDome). Testable avec un faux transport.
 */
class LeclercClient(
    transport: HttpTransport,
    /** Drive choisi dans l'app (null = pas encore choisi). */
    private val store: () -> LeclercStore?,
    /** Recharge la page du drive dans la WebView, pour relier la session au drive choisi. */
    private val rebind: suspend () -> Unit = {},
    /** Drive du compte trouvé sur la page d'accueil (aucun drive n'était choisi). */
    private val onStoreFound: suspend (LeclercStore) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    rateLimitWaitMs: Long = 3_000,
) : RetailerBackend {

    override val retailer = Retailer.LECLERC
    private val who = retailer.label

    private val http = RetailerHttp(transport, who, LeclercRoutes.FORBIDDEN, rateLimitWaitMs, ::classify)

    private data class Cached<T>(val at: Long, val value: T)
    private val cacheTtlMs = 5 * 60 * 1000L
    private val searchCache = HashMap<String, Cached<List<Product>>>()
    private val cacheLock = Mutex()

    /** DataDome répond 403 (ou une page sans contenu de drive) : c'est une vérification anti-robot, pas une déconnexion. */
    private fun classify(r: HttpResponse): CarrefourException? = when {
        r.status == 403 -> CarrefourException.ChallengeRequired(who)
        r.header("x-dd-b") != null && r.status >= 400 -> CarrefourException.ChallengeRequired(who)
        else -> null
    }

    private fun currentStore(): LeclercStore = store() ?: throw CarrefourException.NoDrive(who)

    /** Page HTML d'un drive, vérifiée : un 200 sans contenu de drive = anti-robot ou session expirée. */
    /** Adresse du drive refusée par le site (enregistrée sans le nom de la ville, ou drive fermé) : drive à rechoisir. */
    private fun notFound() = CarrefourException.NoDrive(who, "page du drive introuvable (${store()?.path}) : choisis de nouveau ton drive")

    /**
     * Page HTML du drive, vérifiée. Une page « session expirée » vient souvent d'une session liée à un autre drive :
     * on recharge une fois la page du drive dans la WebView ([rebind]) pour y relier la session, puis on réessaie.
     */
    private suspend fun storePage(url: String, allowRebind: Boolean = true): String {
        val response = try {
            http.call("GET", url, LeclercRoutes.HTML_HEADERS)
        } catch (e: CarrefourException.Http) {
            if (e.status == 404) throw notFound() else throw e
        }
        val html = response.body
        if (LeclercParsers.isStorePage(html)) return html
        if (LeclercParsers.isNotFoundPage(html)) throw notFound()
        if (LeclercParsers.isExpiredPage(html)) {
            if (allowRebind) {
                rebind()
                return storePage(url, allowRebind = false)
            }
            throw CarrefourException.SessionExpired(who, "page « session expirée » du drive ${currentStore().id}")
        }
        throw CarrefourException.ChallengeRequired(who, "la page reçue n'est pas une page de drive")
    }

    /**
     * Sans drive choisi : la page d'accueil dit si le compte est connecté. Connecté → on reprend le drive du compte
     * s'il y figure, sinon « drive à choisir » ; pas connecté → connexion requise.
     */
    override suspend fun sessionProbe(): Cart? {
        if (store() == null) {
            val home = http.call("GET", LeclercRoutes.HOME, LeclercRoutes.HTML_HEADERS).body
            if (!LeclercParsers.isLoggedInHome(home)) throw CarrefourException.SessionExpired(who, "non connecté sur leclercdrive.fr")
            val found = LeclercParsers.storeInPage(home) ?: throw CarrefourException.NoDrive(who)
            onStoreFound(found)
            if (store() == null) throw CarrefourException.NoDrive(who)
        }
        return cart()
    }

    override suspend fun search(query: String, limit: Int): List<Product> =
        searchWithUnavailable(query, Int.MAX_VALUE).filter { it.available }.take(limit)

    /** Garde les produits en rupture (stock 0, `available = false`) pour que le panier les signale. */
    override suspend fun searchWithUnavailable(query: String, limit: Int): List<Product> {
        val s = currentStore()
        val key = s.id + "|" + query.trim().lowercase()
        cacheLock.withLock { searchCache[key]?.takeIf { now() - it.at < cacheTtlMs }?.let { return it.value.take(limit) } }
        val products = LeclercParsers.parseSearch(storePage(LeclercRoutes.search(s, query)))
        cacheLock.withLock { searchCache[key] = Cached(now(), products) }
        return products.take(limit)
    }

    override suspend fun cart(): Cart = LeclercParsers.parseCartPage(storePage(LeclercRoutes.cartPage(currentStore())))

    override suspend fun setQuantity(ean: String, quantity: Int): Cart {
        require(quantity in 0..50) { "Quantité invalide : $quantity" }
        return mutate(ean, quantity, cart().quantityOf(ean))
    }

    override suspend fun addToCart(product: Product, quantity: Int): Cart {
        val current = cart().quantityOf(product.id)
        require(current + quantity in 0..50) { "Quantité invalide : ${current + quantity}" }
        return mutate(product.id, current + quantity, current)
    }

    /**
     * Écriture du panier. La quantité envoyée est la cible ; l'action dit seulement s'il faut monter ou descendre.
     * La réponse ne liste que la ligne modifiée et le total du panier (pas de relecture, pour limiter les requêtes).
     */
    private suspend fun mutate(id: String, quantity: Int, current: Int): Cart {
        val s = currentStore()
        val action = if (quantity >= current) LeclercRoutes.ACTION_ADD else LeclercRoutes.ACTION_SUB
        val payload = buildJsonObject {
            put("eTypeAction", action)
            put("iIdProduit", id)
            put("iQuantite", quantity)
            put("sNoPointLivraison", s.id)
        }.toString()
        val body = "d=" + URLEncoder.encode(payload, Charsets.UTF_8)
        val response = http.call("POST", LeclercRoutes.cartOp(s), LeclercRoutes.FORM_HEADERS, body)
        return LeclercParsers.parseCartEvents(response.body)
            ?: throw if (LeclercParsers.isExpiredPage(response.body)) CarrefourException.SessionExpired(who) else CarrefourException.ChallengeRequired(who)
    }

    /** Les créneaux Leclerc ne sont pas encore relevés : ils se choisissent sur le site. */
    override suspend fun timeslots(max: Int): List<TimeSlot> = emptyList()

    /** Recherche de drives en trois appels (autocomplétion → coordonnées → drives proches), depuis www.leclercdrive.fr. */
    override suspend fun stores(lat: Double, lon: Double, postalCode: String, city: String): List<Store> {
        val place = LeclercParsers.parsePlace(http.call("GET", LeclercRoutes.locatorAutocomplete(postalCode), LeclercRoutes.JSON_HEADERS).body)
        val coords = place?.let { LeclercParsers.parseCoordinates(http.call("GET", LeclercRoutes.locatorCoordinates(it.first), LeclercRoutes.JSON_HEADERS).body) }
            ?: (lat to lon)
        val body = http.call("GET", LeclercRoutes.locatorNearby(coords.first, coords.second, place?.second ?: postalCode), LeclercRoutes.JSON_HEADERS).body
        return LeclercParsers.parseStores(body, place?.third ?: city)
    }

    override fun forgetContext() {
        searchCache.clear()
    }

    companion object {
        /** Drive Leclerc à partir d'un magasin de la liste (hôte et noPR rangés dans `Store.ref`). */
        fun storeOf(s: Store): LeclercStore? {
            val parts = s.ref?.split('|') ?: return null
            val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val noPR = parts.getOrNull(1)?.ifBlank { null } ?: s.id
            val path = parts.getOrNull(2)?.ifBlank { null } ?: "magasin-${s.id}-$noPR"
            return LeclercStore(s.id, noPR, host, s.name, path)
        }
    }
}

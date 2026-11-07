package fr.mescourses.core

import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.cart.ProductMemory
import fr.mescourses.core.carrefour.CarrefourClient
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.CarrefourParsers
import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.core.carrefour.CarrefourTools
import fr.mescourses.core.carrefour.DriveContext
import fr.mescourses.core.carrefour.HttpResponse
import fr.mescourses.core.carrefour.HttpTransport
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.WriteConfirmer
import fr.mescourses.core.carrefour.WriteRequest
import fr.mescourses.core.model.ShoppingItem
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun fixture(name: String) = CarrefourTest::class.java.getResource("/carrefour/$name")!!.readText()

/** Faux transport : rejoue des réponses et enregistre les requêtes. */
class FakeTransport(private val responder: (method: String, path: String, body: String?) -> HttpResponse) : HttpTransport {
    val calls = mutableListOf<Triple<String, String, String?>>()
    override suspend fun request(method: String, path: String, headers: Map<String, String>, body: String?): HttpResponse {
        calls += Triple(method, path, body)
        return responder(method, path, body)
    }
}

private fun ok(body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(200, headers, body)

class CarrefourTest {

    // ---------- Lecteurs ----------

    @Test fun recherche_json_api() {
        val p = CarrefourParsers.parseSearch(fixture("search.json"))
        assertEquals(listOf("3560070000011", "3560070000028"), p.map { it.ean })
        val lait = p.first()
        assertEquals("Lait demi-écrémé bio 1L", lait.name)
        assertEquals(1.29, lait.price)
        assertEquals("1,29 €/L", lait.unitPrice)
        assertEquals("B", lait.nutriscore)
        assertEquals("2e à -50%", lait.promo)
        assertEquals("https://exemple.invalid/lait.jpg", lait.imageUrl)
        assertTrue(lait.organic)
    }

    @Test fun recherche_page_html_nuxt() {
        val p = CarrefourParsers.parseSearch(fixture("search_ssr.html")).single()
        assertEquals("3560070000035", p.ean)
        assertEquals("Lait d'avoine bio 1L", p.name)
        assertEquals("Bjorg", p.brand)
        assertEquals(2.49, p.price)
    }

    @Test fun panier() {
        val cart = CarrefourParsers.parseCart(fixture("cart.json"))
        assertEquals(2, cart.lines.size)
        assertEquals(2, cart.quantityOf("3560070000011"))
        assertEquals(2.58, cart.lines.first().subtotal)
        assertEquals(3.73, cart.total)
        assertEquals(DriveContext("0678-150-7052", "drive_clcv", "0678-150-149"), cart.context)
    }

    @Test fun magasins_tries_par_distance() {
        val stores = CarrefourParsers.parseStores(fixture("stores.json"))
        assertEquals(listOf("0912", "0678"), stores.map { it.id })
        assertEquals("Exempleville", stores.first().city)
        assertEquals(0.8, stores.first().distanceKm)
    }

    @Test fun creneaux_disponibles_seulement() {
        val slots = CarrefourParsers.parseTimeslots(fixture("timeslots.json"))
        assertEquals(2, slots.size)
        assertEquals("2026-11-09", slots[0].date)
        assertEquals("18:00", slots[0].start)
        assertEquals("19:00", slots[0].end)
        assertEquals(2.0, slots[0].price)
    }

    @Test fun detail_produit_json_ld() {
        val d = assertNotNull(CarrefourParsers.parseProductDetail(fixture("product.html")))
        assertEquals("3560070000042", d.product.ean)
        assertEquals(1.39, d.product.price)
        assertEquals("Carrefour Bio", d.product.brand)
    }

    // ---------- Client ----------

    private fun cartAware(): FakeTransport {
        var cartBody = fixture("cart.json")
        return FakeTransport { method, path, body ->
            when {
                path.startsWith("/s?") -> ok(fixture("search.json"))
                path == CarrefourRoutes.CART && method == "GET" -> ok(cartBody)
                path == CarrefourRoutes.CART && method == "PATCH" -> ok("{}")
                path.startsWith("/api/timeslots") -> ok(fixture("timeslots.json"))
                else -> HttpResponse(404, emptyMap(), "")
            }
        }
    }

    @Test fun ajout_envoie_la_quantite_totale_avec_le_contexte_du_drive() = runTest {
        val t = cartAware()
        val client = CarrefourClient(t)
        val lait = client.search("lait").first()
        client.addToCart(lait, 1)
        val patch = t.calls.single { it.first == "PATCH" }
        val item = Json.parseToJsonElement(patch.third!!).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("3", item["counter"]!!.jsonPrimitive.content) // 2 déjà dans le panier + 1
        assertEquals("3560070000011", item["ean"]!!.jsonPrimitive.content)
        assertEquals("0678-150-7052", item["basketServiceId"]!!.jsonPrimitive.content)
        assertEquals("drive_clcv", item["subBasketType"]!!.jsonPrimitive.content)
    }

    @Test fun retrait_par_quantite_zero() = runTest {
        val t = cartAware()
        CarrefourClient(t).setQuantity("3560070000028", 0)
        assertTrue("\"counter\":0" in t.calls.single { it.first == "PATCH" }.third!!)
    }

    @Test fun cache_de_recherche() = runTest {
        val t = cartAware()
        val c = CarrefourClient(t)
        c.search("Lait"); c.search("lait ")
        assertEquals(1, t.calls.count { it.second.startsWith("/s?") })
    }

    @Test fun erreurs_typees() = runTest {
        assertFailsWith<CarrefourException.SessionExpired> {
            CarrefourClient(FakeTransport { _, _, _ -> HttpResponse(401, emptyMap(), "") }).cart()
        }
        assertFailsWith<CarrefourException.ChallengeRequired> {
            CarrefourClient(FakeTransport { _, _, _ -> HttpResponse(403, mapOf("cf-mitigated" to "challenge"), "") }).cart()
        }
        var n = 0
        val retried = CarrefourClient(FakeTransport { _, _, _ -> if (n++ == 0) HttpResponse(429, emptyMap(), "") else ok(fixture("cart.json")) }, rateLimitWaitMs = 0)
        assertEquals(2, retried.cart().lines.size)
        assertFailsWith<CarrefourException.NoDrive> {
            CarrefourClient(FakeTransport { _, _, _ -> ok("{}") }).setQuantity("123", 1)
        }
        assertFailsWith<CarrefourException.Forbidden> {
            CarrefourClient(FakeTransport { _, _, _ -> ok("{}") }).call("POST", "/api/checkout/payment", "{}")
        }
    }

    @Test fun drive_enregistre_utilise_si_panier_vide() = runTest {
        val t = FakeTransport { m, _, _ -> ok(if (m == "GET") "{\"cart\":{}}" else "{}") }
        val client = CarrefourClient(t, savedContext = { DriveContext("9999-1-1", facilityServiceId = "9999-1-2") })
        client.setQuantity("3560070000011", 1)
        assertTrue("9999-1-1" in t.calls.single { it.first == "PATCH" }.third!!)
    }

    // ---------- Outils Gemma ----------

    private fun tools(t: FakeTransport, confirm: Boolean, requests: MutableList<WriteRequest>) =
        CarrefourTools(CarrefourClient(t), WriteConfirmer { r -> requests += r; confirm }).all().associateBy { it.name }

    private fun args(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun six_outils_et_resultats_compacts() = runTest {
        val tools = tools(cartAware(), true, mutableListOf())
        assertEquals(
            setOf("chercher_produit", "details_produit", "voir_panier", "ajouter_au_panier", "modifier_quantite", "creneaux_drive"),
            tools.keys,
        )
        val found = tools.getValue("chercher_produit").execute(args("""{"requete":"lait","max":2}""")) as JsonArray
        val first = found.first().jsonObject
        assertEquals("3560070000011", first["id"]!!.jsonPrimitive.content)
        assertTrue("imageUrl" !in first && "image" !in first.toString()) // pas d'URL d'image envoyée au modèle
    }

    @Test fun ecriture_confirmee_ou_annulee() = runTest {
        val requests = mutableListOf<WriteRequest>()
        val t = cartAware()
        val confirmed = tools(t, true, requests)
        confirmed.getValue("chercher_produit").execute(args("""{"requete":"lait"}"""))
        val r = confirmed.getValue("ajouter_au_panier").execute(args("""{"id":"3560070000011","quantite":2}""")).jsonObject
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals(WriteRequest("3560070000011", "Lait demi-écrémé bio 1L", 1.29, 2, 4), requests.single())

        val t2 = cartAware()
        val cancelled = tools(t2, false, mutableListOf()).getValue("modifier_quantite").execute(args("""{"id":"3560070000028","quantite":0}""")).jsonObject
        assertEquals("false", cancelled["ok"]!!.jsonPrimitive.content)
        assertTrue(t2.calls.none { it.first == "PATCH" }) // rien n'est écrit sans confirmation
    }

    @Test fun erreur_lisible_pour_le_modele() = runTest {
        val tools = CarrefourTools(CarrefourClient(FakeTransport { _, _, _ -> HttpResponse(401, emptyMap(), "") }), WriteConfirmer { true })
        val r = tools.all().first { it.name == "voir_panier" }.execute(args("{}")).jsonObject
        assertEquals("session_expiree", r["erreur"]!!.jsonPrimitive.content)
    }

    // ---------- Panier depuis la liste, avec mémoire des choix ----------

    @Test fun memoire_des_choix() = runTest {
        val memory = object : ProductMemory {
            val map = HashMap<String, Product>()
            override suspend fun get(key: String) = map[key]
            override suspend fun remember(key: String, product: Product) { map[key] = product }
        }
        val t = cartAware()
        val client = CarrefourClient(t)
        val items = listOf(ShoppingItem(name = "Lait", quantity = 1.0, unit = "l"))

        val first = CartPlanner.propose(client, items, memory = memory, pauseMs = 0).toList()
        assertEquals(LineStatus.PROPOSE, first.single().status)
        CartPlanner.fill(client, first, memory, pauseMs = 0).toList()
        assertNotNull(memory.map["lait"])

        val searchesBefore = t.calls.count { it.second.startsWith("/s?") }
        val second = CartPlanner.propose(client, items, memory = memory, pauseMs = 0).toList().single()
        assertTrue(second.remembered)
        assertEquals(searchesBefore, t.calls.count { it.second.startsWith("/s?") }) // pas de nouvelle recherche
    }
}

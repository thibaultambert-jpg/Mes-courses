package fr.mescourses.core

import fr.mescourses.core.auchan.AuchanAuth
import fr.mescourses.core.auchan.AuchanClient
import fr.mescourses.core.auchan.AuchanParsers
import fr.mescourses.core.auchan.AuchanRoutes
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.cart.PriceComparison
import fr.mescourses.core.cart.ProductMemory
import fr.mescourses.core.cart.RetailerBackend
import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.HttpResponse
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.leclerc.LeclercClient
import fr.mescourses.core.leclerc.LeclercParsers
import fr.mescourses.core.leclerc.LeclercRoutes
import fr.mescourses.core.leclerc.LeclercStore
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.retail.Retailer
import fr.mescourses.core.retail.RetailerProductMemory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun res(path: String) = LeclercAuchanTest::class.java.getResource(path)!!.readText()
private fun ok(body: String) = HttpResponse(200, emptyMap(), body)

class LeclercAuchanTest {

    private val store = LeclercStore("053701", host = "fd9-courses.leclercdrive.fr", name = "Test")

    // ---------- Leclerc ----------

    @Test fun leclerc_recherche() {
        val p = LeclercParsers.parseSearch(res("/leclerc/search.html"))
        assertEquals(listOf("2612", "2613"), p.map { it.id })
        val lait = p.first()
        assertEquals("Lait demi-écrémé bio 1L", lait.name)
        assertEquals(1.15, lait.price)
        assertEquals("1,15 € / L", lait.unitPrice)
        assertTrue(lait.available)
        assertTrue(!p[1].available) // stock 0
    }

    @Test fun leclerc_panier_dans_la_page() {
        val cart = LeclercParsers.parseCartPage(res("/leclerc/cart.html"))
        assertEquals(1, cart.lines.size)
        assertEquals("2612", cart.lines[0].ean)
        assertEquals(2, cart.lines[0].quantity)
        assertEquals(2.30, cart.lines[0].subtotal)
        assertEquals(18.18, cart.total)
    }

    @Test fun leclerc_routes() {
        assertEquals("https://fd9-courses.leclercdrive.fr/magasin-053701-053701/recherche.aspx?TexteRecherche=lait%20bio", LeclercRoutes.search(store, "lait bio"))
        assertEquals("https://fd9-courses.leclercdrive.fr/magasin-053701-053701/panier.aspx?op=1", LeclercRoutes.cartOp(store))
    }

    @Test fun leclerc_ajout_envoie_la_quantite_cible() = runTest {
        val transport = FakeTransport { method, path, _ ->
            when {
                method == "GET" && path.contains("zzzznomatchzzz") -> ok(res("/leclerc/cart.html"))
                method == "POST" -> ok(res("/leclerc/cart_events.json"))
                else -> error("inattendu $method $path")
            }
        }
        val client = LeclercClient(transport, { store }, rateLimitWaitMs = 0)
        val cart = client.addToCart(Product(id = "2612", name = "Lait"), 1)
        val post = transport.calls.single { it.first == "POST" }
        val payload = Json.parseToJsonElement(post.third!!).jsonObject
        // Chemin court (sans la ville) : le chemin long renvoie 404.
        assertTrue(post.second.endsWith("/magasin-053701-053701/panier.aspx?op=1"), post.second)
        assertEquals(3, payload["iQuantite"]!!.jsonPrimitive.content.toInt()) // 2 déjà au panier + 1
        assertEquals("1", payload["eTypeAction"]!!.jsonPrimitive.content)
        assertEquals("053701", payload["sNoPointLivraison"]!!.jsonPrimitive.content)
        assertEquals(19.33, cart.total)
        assertEquals(3, cart.quantityOf("2612"))
    }

    @Test fun leclerc_reponse_reelle_du_panier() {
        val cart = assertNotNull(LeclercParsers.parseCartEvents(res("/leclerc/cart_events_real.json")))
        assertEquals(1, cart.quantityOf("135279"))
        assertEquals(6.98, cart.total)
        val withCity = LeclercStore("159201", "159201", "fd14-courses.leclercdrive.fr", "Colombes", "magasin-159201-159201-Colombes-Michelet")
        assertEquals("https://fd14-courses.leclercdrive.fr/magasin-159201-159201/panier.aspx?op=1", LeclercRoutes.cartOp(withCity))
    }

    @Test fun leclerc_retrait_diminue() = runTest {
        val transport = FakeTransport { method, _, _ ->
            if (method == "GET") ok(res("/leclerc/cart.html")) else ok(res("/leclerc/cart_events.json"))
        }
        LeclercClient(transport, { store }, rateLimitWaitMs = 0).setQuantity("2612", 0)
        val payload = transport.calls.single { it.first == "POST" }.third!!
        assertTrue(payload.contains("\"eTypeAction\":2"))
        assertTrue(payload.contains("\"iQuantite\":0"))
    }

    @Test fun leclerc_erreurs() = runTest {
        assertFailsWith<CarrefourException.NoDrive> { LeclercClient(FakeTransport { _, _, _ -> ok("") }, { null }).cart() }
        assertFailsWith<CarrefourException.ChallengeRequired> {
            LeclercClient(FakeTransport { _, _, _ -> HttpResponse(403, emptyMap(), "datadome") }, { store }, rateLimitWaitMs = 0).cart()
        }
        assertFailsWith<CarrefourException.ChallengeRequired> {
            LeclercClient(FakeTransport { _, _, _ -> ok("<html>captcha</html>") }, { store }).cart()
        }
        assertFailsWith<CarrefourException.SessionExpired> {
            LeclercClient(FakeTransport { _, _, _ -> ok("<html>Votre session a expiré</html>") }, { store }).cart()
        }
    }

    @Test fun leclerc_magasins() = runTest {
        val transport = FakeTransport { _, path, _ ->
            when {
                path.contains("/autocomplete/coordinates") -> ok("""{"latitude":47.36,"longitude":0.76}""")
                path.contains("/autocomplete?") -> ok("""{"postalCodes":[{"id":"abc","postalCode":"37700","city":"La Ville-aux-Dames"}]}""")
                path.contains("/MapPoint/nearby") -> ok(res("/leclerc/stores.json"))
                else -> error(path)
            }
        }
        val stores = LeclercClient(transport, { null }).stores(0.0, 0.0, "37700", "")
        assertEquals(2, stores.size)
        val first = LeclercClient.storeOf(stores[0])
        assertEquals(LeclercStore("053701", "053701", "fd9-courses.leclercdrive.fr", "La Ville-aux-Dames", "magasin-053701-053701-La-Ville-aux-Dames"), first)
        // Les routes gardent le nom de la ville : sans lui, certains drives répondent « page introuvable ».
        assertEquals("https://fd9-courses.leclercdrive.fr/magasin-053701-053701/panier.aspx?op=1", LeclercRoutes.cartOp(first!!))
        assertTrue(stores[1].name.contains("relais"))
        assertTrue(transport.calls.last().second.contains("latitude=47.36"))
    }

    @Test fun leclerc_connecte_sans_drive_reprend_le_drive_du_compte() = runTest {
        var chosen: LeclercStore? = null
        val home = "<html>Dernière connexion le dimanche <a href=\"https://fd14-courses.leclercdrive.fr/magasin-092001-092001-Colombes-Michelet/\">Mon drive</a></html>"
        val transport = FakeTransport { _, path, _ ->
            when {
                path == LeclercRoutes.HOME -> ok(home)
                path.contains("zzzznomatchzzz") -> ok(res("/leclerc/cart.html"))
                else -> error(path)
            }
        }
        val client = LeclercClient(transport, { chosen }, onStoreFound = { chosen = it })
        val cart = client.sessionProbe()
        assertEquals(LeclercStore("092001", "092001", "fd14-courses.leclercdrive.fr", "Colombes Michelet", "magasin-092001-092001-Colombes-Michelet"), chosen)
        assertEquals(1, cart?.lines?.size)
    }

    @Test fun leclerc_chemin_en_aspx() {
        // urlSiteCourse donne la page d'accueil en .aspx : les autres pages partent du chemin sans .aspx.
        val st = LeclercStore("159201", host = "fd14-courses.leclercdrive.fr", path = "magasin-159201-159201-colombes-michelet.aspx")
        assertEquals("https://fd14-courses.leclercdrive.fr/magasin-159201-159201-colombes-michelet/recherche.aspx?TexteRecherche=lait", LeclercRoutes.search(st, "lait"))
        assertEquals("https://fd14-courses.leclercdrive.fr/magasin-159201-159201/panier.aspx?op=1", LeclercRoutes.cartOp(st))
        assertEquals("https://fd14-courses.leclercdrive.fr/magasin-159201-159201-colombes-michelet.aspx", LeclercRoutes.storePage(st))
        assertEquals("magasin-159201-159201-colombes-michelet.aspx", LeclercParsers.pathOf("https://fd14-courses.leclercdrive.fr/magasin-159201-159201-colombes-michelet.aspx"))
        val noAspx = LeclercStore("053701", host = "fd9-courses.leclercdrive.fr")
        assertEquals("https://fd9-courses.leclercdrive.fr/magasin-053701-053701/", LeclercRoutes.storePage(noAspx))
    }

    @Test fun leclerc_page_du_drive_introuvable() = runTest {
        val e = assertFailsWith<CarrefourException.NoDrive> {
            LeclercClient(FakeTransport { _, _, _ -> ok("<html>Désolé, la page demandée n'a pas pu être trouvée.</html>") }, { store }).cart()
        }
        assertTrue(e.message!!.contains("choisis de nouveau"), e.message)
        assertFailsWith<CarrefourException.NoDrive> {
            LeclercClient(FakeTransport { _, _, _ -> HttpResponse(404, emptyMap(), "") }, { store }, rateLimitWaitMs = 0).cart()
        }
    }

    @Test fun leclerc_sans_drive_ni_connexion() = runTest {
        val client = LeclercClient(FakeTransport { _, _, _ -> ok("<html>Me connecter</html>") }, { null })
        assertFailsWith<CarrefourException.SessionExpired> { client.sessionProbe() }
        val logged = LeclercClient(FakeTransport { _, _, _ -> ok("<html>Dernière connexion le lundi</html>") }, { null })
        assertFailsWith<CarrefourException.NoDrive> { logged.sessionProbe() }
    }

    @Test fun leclerc_session_reliee_au_drive_puis_reessai() = runTest {
        var rebinds = 0
        val transport = FakeTransport { _, _, _ -> if (rebinds == 0) ok("<html>Votre session a expiré</html>") else ok(res("/leclerc/cart.html")) }
        val cart = LeclercClient(transport, { store }, rebind = { rebinds++ }).cart()
        assertEquals(1, rebinds)
        assertEquals(1, cart.lines.size)
    }

    // ---------- Auchan ----------

    @Test fun auchan_recherche() {
        val p = AuchanParsers.parseSearch(res("/auchan/search.html"))
        assertEquals(listOf("P1", "P3", "P2"), p.map { it.id }) // le sponsorisé passe en dernier
        val lait = p.first()
        assertEquals("Lait demi-écrémé bio 1L", lait.name)
        assertEquals(1.22, lait.price)
        assertEquals("1,22€ / l", lait.unitPrice)
        assertEquals("O1", lait.identifiers["offerId"])
        assertEquals("S1", lait.identifiers["sellerId"])
        assertTrue(!p.first { it.id == "P2" }.available)
        // Images chargées en différé : data-src, puis <source srcset> ; jamais l'image vide ni le remplaçant.
        assertEquals("https://cdn.auchan.fr/media/lait_540.jpg", lait.imageUrl)
        assertEquals("https://cdn.auchan.fr/media/oeufs_320.webp", p.first { it.id == "P3" }.imageUrl)
        assertNull(p.first { it.id == "P2" }.imageUrl)
    }

    @Test fun auchan_panier() {
        val raw = assertNotNull(AuchanParsers.parseCart(res("/auchan/cart.json")))
        assertEquals("CART1", raw.id)
        assertEquals("Auchan Drive Test", raw.store)
        val cart = raw.toCart(mapOf("P2" to "Beurre doux"))
        assertEquals(listOf("P1" to 2, "P2" to 1), cart.lines.map { it.ean to it.quantity })
        assertEquals("Beurre doux", cart.lines[1].name)
        assertEquals(2.44 + 2.10, cart.total)
    }

    private fun auchanTransport() = FakeTransport { method, path, _ ->
        when {
            path.startsWith(AuchanRoutes.API + "/checkout/v1/carts/mine") -> ok(res("/auchan/cart.json"))
            path.startsWith(AuchanRoutes.API) && method == "POST" -> ok(res("/auchan/cart.json"))
            path.startsWith(AuchanRoutes.ORIGIN + "/recherche") -> ok(res("/auchan/search.html"))
            path == AuchanRoutes.CART_UPDATE -> ok("{}")
            else -> error("inattendu $method $path")
        }
    }

    private val auth = AuchanAuth(mapOf("authorization" to "Bearer x", "client-id" to "c"), "00000000-0000-0000-0000-000000000000")

    @Test fun auchan_ajout_nouveau_produit_par_la_recherche() = runTest {
        val transport = auchanTransport()
        var store: String? = null
        val client = AuchanClient(transport, { auth }, onStore = { store = it }, rateLimitWaitMs = 0)
        // Produit repris de la mémoire : pas d'offre, elle est retrouvée par une recherche.
        client.addToCart(Product(id = "P3", name = "Oeufs"), 2)
        val update = transport.calls.single { it.second == AuchanRoutes.CART_UPDATE }
        val body = Json.parseToJsonElement(update.third!!).jsonObject
        val item = body["items"]!!.jsonArray[0].jsonObject
        assertEquals("O3", item["offerId"]!!.jsonPrimitive.content)
        assertEquals("S1", item["sellerId"]!!.jsonPrimitive.content)
        assertEquals(2, item["desiredQuantity"]!!.jsonPrimitive.content.toInt())
        assertEquals("CART1", body["cartId"]!!.jsonPrimitive.content)
        assertEquals("Auchan Drive Test", store)
    }

    @Test fun auchan_ligne_existante_quantite_absolue() = runTest {
        val transport = auchanTransport()
        AuchanClient(transport, { auth }, rateLimitWaitMs = 0).addToCart(Product(id = "P1", name = "Lait"), 1)
        val post = transport.calls.single { it.first == "POST" }
        assertTrue(post.second.contains("/carts/CART1/items"))
        val line = Json.parseToJsonElement(post.third!!).jsonArray[0].jsonObject
        assertEquals("L1", line["id"]!!.jsonPrimitive.content)
        assertEquals(3, line["desiredQuantity"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun auchan_sans_jetons_session_expiree() = runTest {
        var refreshed = 0
        val client = AuchanClient(auchanTransport(), { null }, refreshAuth = { refreshed++ })
        assertFailsWith<CarrefourException.SessionExpired> { client.cart() }
        assertEquals(1, refreshed)
    }

    // ---------- Mémoire et comparaison ----------

    @Test fun memoire_par_enseigne() = runTest {
        val store = HashMap<String, Product>()
        val inner = object : ProductMemory {
            override suspend fun get(key: String) = store[key]
            override suspend fun remember(key: String, product: Product) { store[key] = product }
        }
        RetailerProductMemory(Retailer.CARREFOUR, inner).remember("lait", Product("1", "Lait C"))
        RetailerProductMemory(Retailer.LECLERC, inner).remember("lait", Product("2", "Lait L"))
        assertEquals(setOf("lait", "leclerc:lait"), store.keys)
        assertNull(RetailerProductMemory(Retailer.AUCHAN, inner).get("lait"))
    }

    private class FakeBackend(override val retailer: Retailer, val prices: Map<String, Double>) : RetailerBackend {
        override suspend fun search(query: String, limit: Int) =
            prices.filterKeys { query.contains(it) }.map { (k, v) -> Product("$k-${retailer.name}", k, price = v) }
        override suspend fun cart() = Cart(emptyList(), null, null)
        override suspend fun addToCart(product: Product, quantity: Int) = cart()
        override suspend fun setQuantity(ean: String, quantity: Int) = cart()
        override suspend fun timeslots(max: Int) = emptyList<TimeSlot>()
        override suspend fun sessionProbe(): Cart? = null
    }

    @Test fun comparaison_des_prix() = runTest {
        val items = listOf(ShoppingItem(name = "lait"), ShoppingItem(name = "beurre"))
        val backends = listOf(
            FakeBackend(Retailer.CARREFOUR, mapOf("lait" to 1.2, "beurre" to 2.5)),
            FakeBackend(Retailer.LECLERC, mapOf("lait" to 1.0, "beurre" to 2.2)),
            FakeBackend(Retailer.AUCHAN, mapOf("lait" to 0.5)),
        )
        val updates = PriceComparison.compare(backends, items, false, { null }, pauseMs = 0).toList()
        val last = updates.groupBy { it.retailer }.mapValues { it.value.last() }
        assertTrue(last.values.all { it.done })
        assertEquals(3.7, last.getValue(Retailer.CARREFOUR).total)
        assertEquals(3.2, last.getValue(Retailer.LECLERC).total)
        assertEquals(1, last.getValue(Retailer.AUCHAN).missing)
        // Auchan est moins cher mais n'a qu'un article sur deux : Leclerc est la moins chère des listes complètes.
        assertEquals(Retailer.LECLERC, PriceComparison.cheapest(last.values))
    }

    @Test fun comparaison_enseigne_non_prete() = runTest {
        val items = listOf(ShoppingItem(name = "lait"))
        val updates = PriceComparison.compare(
            listOf(FakeBackend(Retailer.LECLERC, mapOf("lait" to 1.0))), items, false, { null },
            ready = { "Connecte-toi" }, pauseMs = 0,
        ).toList()
        assertEquals("Connecte-toi", updates.last().problem)
        assertEquals(LineStatus.A_CHERCHER, updates.last().lines.single().status)
        assertNull(PriceComparison.cheapest(updates))
    }
}

class DebugReaderTest {
    @Test fun resume_leclerc_et_auchan() {
        val l = fr.mescourses.core.retail.DebugReader.summarize(Retailer.LECLERC, res("/leclerc/search.html"))
        assertTrue(l.contains("Page de drive reconnue"), l)
        assertTrue(l.contains("2 produit(s) lu(s), dont 1 en rupture"), l)
        val a = fr.mescourses.core.retail.DebugReader.summarize(Retailer.AUCHAN, res("/auchan/search.html"))
        assertTrue(a.contains("photo : https://cdn.auchan.fr/media/lait_540.jpg"), a)
        val blocked = fr.mescourses.core.retail.DebugReader.summarize(Retailer.LECLERC, "<html>captcha</html>")
        assertTrue(blocked.contains("anti-robot"), blocked)
    }
}

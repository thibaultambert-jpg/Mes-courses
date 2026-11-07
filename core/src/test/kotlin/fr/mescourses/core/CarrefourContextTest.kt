package fr.mescourses.core

import fr.mescourses.core.cart.RetailerSession
import fr.mescourses.core.mcp.McpCallResult
import fr.mescourses.core.mcp.McpToolClient
import fr.mescourses.core.mcp.RemoteTool
import fr.mescourses.core.mcp.Retailer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CarrefourContextTest {

    private class FakeCarrefour(private val cartJson: String) : McpToolClient {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        private fun t(name: String, schema: String) = RemoteTool(name, "", Json.parseToJsonElement(schema) as JsonObject)
        override suspend fun listTools() = listOf(
            t("submit_checkout_payment", """{"type":"object","properties":{}}"""),
            t("search_products", """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""),
            t("get_cart", """{"type":"object","properties":{}}"""),
            t("add_item_to_cart", """{"type":"object","properties":{"ean":{"type":"string"},"counter":{"type":"integer"},"basketServiceId":{"type":"string"},"subBasketType":{"type":"string"}},"required":["ean","counter","basketServiceId","subBasketType"]}"""),
        )
        override suspend fun call(name: String, arguments: JsonObject): McpCallResult {
            calls += name to arguments
            return when (name) {
                "search_products" -> McpCallResult("""{"data":[{"id":"111","attributes":{"ean":"3560070000000","title":"Lait demi-écrémé"}}]}""", false)
                "get_cart" -> McpCallResult(cartJson, false)
                else -> McpCallResult("{}", false)
            }
        }
        override suspend fun close() = Unit
    }

    @Test fun parametres_de_contexte_lus_dans_le_panier() = runTest {
        val fake = FakeCarrefour("""{"cart":{"items":[],"basketServiceId":"BSV-42","subBaskets":[{"subBasketType":"drive_clcv"}]}}""")
        val session = RetailerSession.open(Retailer.CARREFOUR, fake)
        val product = session.search("lait").single()
        session.addToCart(product, 2)
        session.addToCart(product, 1)

        val adds = fake.calls.filter { it.first == "add_item_to_cart" }.map { it.second.toString() }
        assertEquals(
            """{"ean":"3560070000000","counter":2,"basketServiceId":"BSV-42","subBasketType":"drive_clcv"}""",
            adds.first(),
        )
        assertEquals(1, fake.calls.count { it.first == "get_cart" }) // contexte mis en cache
        assertEquals(0, fake.calls.count { it.first == "submit_checkout_payment" })
    }

    @Test fun erreur_claire_si_contexte_absent() = runTest {
        val session = RetailerSession.open(Retailer.CARREFOUR, FakeCarrefour("""{"cart":{}}"""))
        val product = session.search("lait").single()
        val e = assertFailsWith<IllegalStateException> { session.addToCart(product) }
        kotlin.test.assertTrue("basketServiceId" in e.message!!)
    }
}

package fr.mescourses.core

import fr.mescourses.core.mcp.McpCallResult
import fr.mescourses.core.mcp.ProductParser
import fr.mescourses.core.mcp.RemoteTool
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.mcp.RetailerToolMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RetailerToolMatcherTest {

    private fun tool(name: String, schema: String) =
        RemoteTool(name, "", Json.parseToJsonElement(schema) as JsonObject)

    /** Serveur multi-enseignes façon fr-grocery-mcp (paramètre `retailer` avec enum). */
    private val multiRetailer = listOf(
        tool("list_stores", """{"type":"object","properties":{"retailer":{"type":"string","enum":["leclerc","coursesu"]}}}"""),
        tool("search_products", """{"type":"object","properties":{"retailer":{"type":"string","enum":["leclerc","coursesu"]},"query":{"type":"string"},"limit":{"type":"number"}},"required":["retailer","query"]}"""),
        tool("add_to_cart", """{"type":"object","properties":{"retailer":{"type":"string","enum":["leclerc","coursesu"]},"product_id":{"type":"string"},"quantity":{"type":"number"}},"required":["retailer","product_id"]}"""),
        tool("get_cart", """{"type":"object","properties":{"retailer":{"type":"string","enum":["leclerc","coursesu"]}}}"""),
    )

    /** Serveur mono-enseigne avec des noms différents. */
    private val carrefourLike = listOf(
        tool("session_status", """{"type":"object","properties":{}}"""),
        tool("search", """{"type":"object","properties":{"keyword":{"type":"string"},"page":{"type":"integer"}},"required":["keyword"]}"""),
        tool("cart_add_item", """{"type":"object","properties":{"ean":{"type":"string"},"qty":{"type":"integer"}},"required":["ean"]}"""),
        tool("view_cart", """{"type":"object","properties":{}}"""),
    )

    /** Extrait réaliste de fr-grocery-mcp (paramètre `store` = enseigne). */
    private val frGrocery = listOf(
        tool("find_stores", """{"type":"object","properties":{"store":{"type":"string","enum":["leclerc","superu"]},"query":{"type":"string"}},"required":["store","query"]}"""),
        tool("search_products", """{"type":"object","properties":{"store":{"type":"string","enum":["leclerc","superu"]},"query":{"type":"string"},"limit":{"type":"integer"}},"required":["store","query"]}"""),
        tool("add_to_cart", """{"type":"object","properties":{"store":{"type":"string","enum":["leclerc","superu"]},"product_id":{"type":"string"},"quantity":{"type":"integer"}},"required":["store","product_id"]}"""),
        tool("remove_from_cart", """{"type":"object","properties":{"store":{"type":"string","enum":["leclerc","superu"]},"product_id":{"type":"string"}}}"""),
        tool("get_cart", """{"type":"object","properties":{"store":{"type":"string","enum":["leclerc","superu"]}},"required":["store"]}"""),
    )

    /** Extrait réaliste de CarrefourDriveMCP, y compris un outil de paiement à ne JAMAIS utiliser. */
    private val carrefourDrive = listOf(
        tool("submit_checkout_payment", """{"type":"object","properties":{"checkout_type":{"type":"string"}}}"""),
        tool("validate_checkout_summary", """{"type":"object","properties":{}}"""),
        tool("autocomplete_search", """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""),
        tool("search_products", """{"type":"object","properties":{"q":{"type":"string"},"page":{"type":"integer"}},"required":["q"]}"""),
        tool("get_cart", """{"type":"object","properties":{}}"""),
        tool("add_item_to_cart", """{"type":"object","properties":{"ean":{"type":"string"},"counter":{"type":"integer"},"basketServiceId":{"type":"string"},"subBasketType":{"type":"string"}},"required":["ean","counter","basketServiceId","subBasketType"]}"""),
        tool("get_eligible_drive_stores", """{"type":"object","properties":{"postalCode":{"type":"string"}}}"""),
    )

    @Test fun fr_grocery_mcp() {
        val m = RetailerToolMatcher.match(frGrocery, Retailer.LECLERC)
        assertEquals("search_products", m.searchTool)
        assertEquals("query", m.searchQueryArg)
        assertEquals("add_to_cart", m.addTool)
        assertEquals("product_id", m.addProductArg)
        assertEquals(mapOf("store" to "leclerc"), m.fixedArgs)
        assertEquals(emptyList(), m.addContextArgs)
    }

    @Test fun carrefour_drive_mcp() {
        val m = RetailerToolMatcher.match(carrefourDrive, Retailer.CARREFOUR)
        assertEquals("search_products", m.searchTool)
        assertEquals("q", m.searchQueryArg)
        assertEquals("add_item_to_cart", m.addTool)
        assertEquals("ean", m.addProductArg)
        assertEquals("counter", m.addQuantityArg)
        assertEquals(listOf("basketServiceId", "subBasketType"), m.addContextArgs)
    }

    @Test fun outils_dangereux_jamais_utilises() {
        listOf("submit_checkout_payment", "validate_checkout_slot", "remove_from_cart", "clear_cart", "submitPayment", "place_order")
            .forEach { kotlin.test.assertFalse(RetailerToolMatcher.isSafe(it), it) }
        listOf("search_products", "add_to_cart", "get_cart", "add_item_to_cart", "set_quantity")
            .forEach { kotlin.test.assertTrue(RetailerToolMatcher.isSafe(it), it) }
    }

    @Test fun produits_format_json_api() {
        val text = """{"data":[{"type":"product","id":"3017620422003","attributes":{"ean":"3017620422003","title":"Nutella 400g",
            "brand":"Ferrero","offers":{"3017620422003":{"0001":{"attributes":{"price":{"price":3.49,"perUnitLabel":"8,73 €/kg"}}}}}}}]}"""
        val p = ProductParser.parse(McpCallResult(text, isError = false)).single()
        assertEquals("Nutella 400g", p.name)
        assertEquals(3.49, p.price)
        assertEquals("3017620422003", p.idFor("ean"))
    }

    @Test fun multi_enseignes() {
        val m = RetailerToolMatcher.match(multiRetailer, Retailer.LECLERC)
        assertEquals("search_products", m.searchTool)
        assertEquals("query", m.searchQueryArg)
        assertEquals("add_to_cart", m.addTool)
        assertEquals("product_id", m.addProductArg)
        assertEquals("quantity", m.addQuantityArg)
        assertEquals("get_cart", m.cartTool)
        assertEquals(mapOf("retailer" to "leclerc"), m.fixedArgs)
    }

    @Test fun noms_differents() {
        val m = RetailerToolMatcher.match(carrefourLike, Retailer.CARREFOUR)
        assertEquals("search", m.searchTool)
        assertEquals("keyword", m.searchQueryArg)
        assertEquals("cart_add_item", m.addTool)
        assertEquals("ean", m.addProductArg)
        assertEquals("qty", m.addQuantityArg)
        assertEquals("view_cart", m.cartTool)
        assertEquals(emptyMap(), m.fixedArgs)
    }

    @Test fun lecture_des_produits_formats_varies() {
        val text = """Voici les résultats :
            {"total":2,"results":{"items":[
              {"ean":"3017620422003","title":"Pâte à tartiner","brand":{"name":"Nutella"},"price":{"value":"3,49 €"},"unitPrice":"8,73 €/kg"},
              {"ean":"123","title":"Farine de blé T45","price":0.89,"available":true},
              {"ean":"456","title":"Indisponible","available":false}
            ]}}"""
        val products = ProductParser.parse(McpCallResult(text, isError = false))
        assertEquals(3, products.size)
        assertEquals("Nutella", products[0].brand)
        assertEquals(3.49, products[0].price)
        assertEquals("8,73 €/kg", products[0].unitPrice)
        assertEquals(false, products[2].available)
    }
}

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

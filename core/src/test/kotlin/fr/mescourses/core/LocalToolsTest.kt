package fr.mescourses.core

import fr.mescourses.core.agent.LocalTools
import fr.mescourses.core.recipes.WebResult
import fr.mescourses.core.recipes.WebSearch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalToolsTest {
    private val recipes = InMemoryRecipes()
    private val shopping = InMemoryShopping()
    private val plan = InMemoryPlan()
    private val web = object : WebSearch {
        override suspend fun search(query: String, limit: Int) = listOf(
            WebResult("Blog", "https://blog.example/x"),
            WebResult("Gratin - Marmiton", "https://www.marmiton.org/gratin"),
        )
    }
    private val tools = LocalTools(recipes, shopping, plan, web, importer = null, today = { LocalDate.of(2026, 11, 7) })
        .all().associateBy { it.name }

    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject
    private suspend fun run(name: String, json: String) = tools.getValue(name).execute(args(json)).jsonObject

    @Test fun schemas_valides() {
        // Chaque outil doit produire une description JSON lisible par LiteRT-LM.
        tools.values.forEach { Json.parseToJsonElement(it.openApiJson()) }
        assertTrue("get_recipe_from_url" !in tools) // pas d'importeur => outil masqué
    }

    @Test fun parcours_complet() = runTest {
        // 1. Le modèle crée une recette (arguments un peu approximatifs : servings en chaîne)
        val saved = run("save_recipe", """{"title":"Gratin dauphinois","servings":"4",
            "ingredients":["1 kg de pommes de terre","50 cl de crème","1 gousse d'ail"],"steps":["Éplucher","Cuire"]}""")
        val id = saved["donnees"]!!.jsonObject["id"]!!.jsonPrimitive.long

        // 2. Il le planifie pour lundi soir, 2 personnes
        val planned = run("plan_meal", """{"recipe_id":$id,"date":"lundi","meal":"diner","servings":2}""")
        assertEquals(true, planned["ok"]!!.jsonPrimitive.content.toBoolean(), planned.toString())
        assertEquals("2026-11-09", plan.state.value.single().date)

        // 3. Ingrédients ajustés pour 2 + un article libre
        run("add_recipe_to_shopping_list", """{"recipe_id":$id,"servings":2}""")
        run("add_to_shopping_list", """{"items":"papier toilette, 2 kg de pommes de terre"}""")
        val list = run("get_shopping_list", "{}")
        val lines = list["donnees"].toString()
        assertTrue("2,5 kg Pommes de terre" in lines, lines) // 500 g + 2 kg fusionnés
        assertTrue("Papier toilette" in lines, lines)
    }

    @Test fun id_invente_refuse() = runTest {
        val r = run("plan_meal", """{"recipe_id":42,"date":"demain"}""")
        assertEquals("false", r["ok"]!!.jsonPrimitive.content)
    }

    @Test fun recherche_web_privilegie_les_sites_de_recettes() = runTest {
        val r = run("search_recipes_web", """{"query":"gratin"}""")
        assertTrue(r["donnees"].toString().indexOf("marmiton") < r["donnees"].toString().indexOf("blog.example"))
    }
}

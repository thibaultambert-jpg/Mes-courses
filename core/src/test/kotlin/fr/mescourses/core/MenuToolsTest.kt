package fr.mescourses.core

import fr.mescourses.core.menu.MenuTools
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuToolsTest {
    private val recipes = InMemoryRecipes()
    private val draft = InMemoryDraft()
    private val tools = MenuTools(draft, recipes) { Portions(2, 1) }.all().associateBy { it.name }

    private suspend fun run(name: String, json: String) =
        tools.getValue(name).execute(Json.parseToJsonElement(json) as JsonObject).jsonObject

    private suspend fun seed() {
        recipes.save(Recipe(title = "Gratin dauphinois", status = RecipeStatus.FAVORI))
        recipes.save(Recipe(title = "Lasagnes à la bolognaise", status = RecipeStatus.BANNI))
    }

    @Test fun proposition_rattache_les_favoris_et_refuse_les_plats_bannis() = runTest {
        seed()
        val r = run("propose_menu", """{"plats":["Velouté de potimarron — avec des graines de courge",
            "Gratin Dauphinois","Lasagnes à la bolognaise — classique","Dahl de lentilles corail"]}""")
        assertTrue("Lasagnes" in r["message"]!!.jsonPrimitive.content)
        val d = draft.state.value
        assertEquals(listOf("Velouté de potimarron", "Gratin dauphinois", "Dahl de lentilles corail"), d.map { it.title })
        assertEquals("avec des graines de courge", d[0].description)
        assertEquals(1L, d[1].recipeId) // favori réutilisé, pas de génération
        assertNull(d[0].recipeId)
        assertTrue(d.all { it.portions == Portions(2, 1) })
    }

    @Test fun bilan_de_mutualisation_renvoye_au_modele() = runTest {
        val r = run("propose_menu", """{"plats":[
            "Tarte aux poireaux — ingrédients : poireaux, crème fraîche, œufs, sel",
            "Velouté de poireaux — ingrédients : poireau, pommes de terre, crème fraîche épaisse",
            "Curry de pois chiches — ingrédients : pois chiches, lait de coco, épinards, poivre"]}""")
        val msg = r["message"]!!.jsonPrimitive.content
        assertTrue("Ingrédients partagés : poireaux (plats 1, 2), crème fraîche (plats 1, 2)" in msg, msg)
        assertTrue("pois chiches (plat 3)" in msg, msg)
        assertTrue("sel (" !in msg && "poivre" !in msg, msg) // produits du placard ignorés
        assertTrue("Équilibre à corriger" in msg && "plat 2 sans protéine" in msg, msg)
        assertEquals(listOf("poireaux", "crème fraîche", "œufs", "sel"), draft.state.value[0].mainIngredients)

        val replaced = run("replace_dish", """{"numero":3,"titre":"Gratin de poireaux","ingredients":["poireaux","pommes de terre","comté"]}""")
        assertTrue("pommes de terre (plats 2, 3)" in replaced["message"]!!.jsonPrimitive.content)
    }

    @Test fun invites_ajoutes_a_un_seul_plat() = runTest {
        run("propose_menu", """{"plats":["Poulet rôti — légumes","Soupe de poireaux"]}""")
        run("add_guests", """{"numero":2,"adultes":3,"enfants":2}""")
        assertEquals(Portions(2, 1), draft.state.value[0].portions)
        assertEquals(Portions(5, 3), draft.state.value[1].portions)
        run("set_dish_people", """{"numero":1,"adultes":4}""")
        assertEquals(Portions(4, 0), draft.state.value[0].portions)
    }

    @Test fun remplacement_garde_les_convives_et_refuse_un_plat_banni() = runTest {
        seed()
        run("propose_menu", """{"plats":["Quiche","Salade"]}""")
        run("add_guests", """{"numero":1,"adultes":1}""")
        val refused = run("replace_dish", """{"numero":1,"titre":"Lasagnes à la bolognaise"}""")
        assertEquals("false", refused["ok"]!!.jsonPrimitive.content)
        run("replace_dish", """{"numero":"1","titre":"Tarte aux poireaux"}""")
        assertEquals("Tarte aux poireaux", draft.state.value[0].title)
        assertEquals(Portions(3, 1), draft.state.value[0].portions)
    }

    @Test fun ajout_suppression_et_numero_invalide() = runTest {
        run("propose_menu", """{"plats":["A plat","B plat"]}""")
        run("add_dish", """{"titre":"Curry de pois chiches"}""")
        run("remove_dish", """{"numero":1}""")
        assertEquals(listOf("B plat", "Curry de pois chiches"), draft.state.value.map { it.title })
        assertEquals("false", run("remove_dish", """{"numero":9}""")["ok"]!!.jsonPrimitive.content)
    }
}

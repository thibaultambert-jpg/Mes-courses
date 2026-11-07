package fr.mescourses.core

import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.RecipeResponseParser
import fr.mescourses.core.menu.RecipeStepsWriter
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.Pantry
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingCleanupTest {

    @Test fun produits_de_placard() {
        listOf(
            "sel", "Sel et poivre du moulin", "poivre noir", "huile d'olive bio", "Vinaigre de cidre", "mayonnaise",
            "ketchup", "moutarde à l'ancienne", "sauce soja", "beurre doux", "lait demi-écrémé", "fleur de sel", "eau",
        ).forEach { assertTrue(Pantry.isStaple(it), it) }
        listOf("lait de coco", "sauce tomate", "beurre de cacahuète", "poivron rouge", "persil", "selle d'agneau", "pâte feuilletée pur beurre")
            .forEach { assertFalse(Pantry.isStaple(it), it) }
    }

    @Test fun aliment_seul() {
        assertEquals("poulet", FoodName.clean("morceaux de poulet"))
        assertEquals("persil", FoodName.clean("Quelques brins de persil frais bio"))
        assertEquals("ail", FoodName.clean(IngredientParser.parse("2 gousses d'ail").name))
        assertEquals("citron", FoodName.clean("jus de citron"))
        assertEquals("oignons", FoodName.clean("oignons émincés"))
        assertEquals("carottes", FoodName.clean("carottes (environ 500 g), pelées"))
        assertEquals("riz complet", FoodName.clean("riz complet bio"))
        assertEquals("pommes de terre", FoodName.clean("pommes de terre"))
    }

    @Test fun recette_vers_liste_sans_placard() {
        val r = Recipe(
            title = "Poulet",
            servings = 2,
            ingredients = IngredientParser.parseAll(
                listOf("400 g de morceaux de poulet", "1 c. à soupe d'huile d'olive", "sel, poivre", "quelques brins de persil frais", "20 g de beurre", "2 œufs"),
            ),
        )
        val items = ShoppingListMerger.fromRecipe(r, 2.0)
        assertEquals(listOf("poulet", "persil", "œufs"), items.map { it.name })
    }

    @Test fun oeufs_par_douze() {
        fun eggs(vararg q: Double) = ShoppingListMerger.merge(q.map { ShoppingItem(name = "œufs", quantity = it) } + ShoppingItem(name = "jaune d'oeuf", quantity = 1.0))
            .single()
        assertEquals(12.0, eggs(2.0).quantity)
        assertEquals("Œufs", eggs(2.0).name)
        assertEquals(24.0, eggs(10.0, 4.0).quantity)
        assertEquals("oeufs x12", CartPlanner.searchQuery(ShoppingItem(name = "Œufs", quantity = 12.0)))
    }

    @Test fun ingredients_en_objets() {
        val r = RecipeResponseParser.parse(
            """{"titre":"X","personnes":4,"ingredients":[{"aliment":"poulet","quantite":600,"unite":"g"},{"aliment":"oignon","quantite":2,"unite":""},{"aliment":"persil"}]}""",
            "X", 4,
        )
        assertNotNull(r)
        assertEquals(listOf("poulet", "oignon", "persil"), r.ingredients.map { it.name })
        assertEquals(600.0, r.ingredients[0].quantity)
        assertEquals("g", r.ingredients[0].unit)
        assertEquals(2.0, r.ingredients[1].quantity)
        assertNull(r.ingredients[1].unit)
        assertTrue(r.steps.isEmpty())
    }

    @Test fun etapes_a_la_demande() = runTest {
        val recipe = Recipe(
            title = "Velouté", servings = 4, tags = listOf("menu", MenuValidator.STEPS_PENDING),
            ingredients = IngredientParser.parseAll(listOf("1 kg de potimarron")),
        )
        var prompt = ""
        val writer = RecipeStepsWriter(TextGenerator { _, p -> prompt = p; """{"preparation_min":10,"cuisson_min":25,"etapes":["Couper","Cuire","Mixer"],"conseil":"Servir chaud"}""" })
        assertTrue(writer.needsSteps(recipe))
        val done = writer.complete(recipe)
        assertNotNull(done)
        assertTrue("1 kg de potimarron" in prompt)
        assertEquals(3, done.steps.size)
        assertEquals(25, done.cookMinutes)
        assertFalse(writer.needsSteps(done))
        assertEquals("Servir chaud", done.notes)
    }
}

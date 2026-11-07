package fr.mescourses.core

import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuValidatorTest {
    private val recipes = InMemoryRecipes()
    private val shopping = InMemoryShopping()
    private val plan = InMemoryPlan()
    private val draft = InMemoryDraft()
    private val prompts = mutableListOf<String>()

    private val llm = TextGenerator { _, prompt ->
        prompts += prompt
        when {
            "Velouté" in prompt -> """Voici la recette :
                ```json
                {"titre":"Velouté","personnes":4,"preparation_min":15,"cuisson_min":25,
                 "ingredients":["1 kg de potimarron","2 oignons","50 cl de bouillon"],
                 "etapes":["Couper","Cuire","Mixer"]}
                ```"""
            else -> "Désolé, je ne sais pas."
        }
    }

    private val validator = MenuValidator(llm, recipes, shopping, plan, draft, childFactor = { 0.5 }, today = { LocalDate.of(2026, 11, 7) })

    @Test fun validation_complete() = runTest {
        val favId = recipes.save(
            Recipe(
                title = "Gratin dauphinois", servings = 4, status = RecipeStatus.FAVORI, timesCooked = 2,
                ingredients = IngredientParser.parseAll(listOf("1 kg de pommes de terre", "50 cl de crème")),
            ),
        )
        draft.set(
            listOf(
                MenuDish("Velouté de potimarron", "doux", Portions(2, 2), null), // 3 portions -> recette pour 3
                MenuDish("Gratin dauphinois", "", Portions(5, 2), favId), // invités : 6 portions
                MenuDish("Plat impossible", "", Portions(2, 0), null),
            ),
        )

        val result = validator.validate()

        assertEquals(1, result.newRecipes)
        assertEquals(1, result.reused)
        assertEquals(listOf("Plat impossible"), result.failures)
        assertTrue("pour 3 personnes" in prompts.first())
        // La recette suivante est invitée à réutiliser ce que la première a fait acheter.
        assertTrue("potimarron" in prompts.last() && "à réutiliser en priorité" in prompts.last(), prompts.last())

        val velo = recipes.state.value.first { it.title == "Velouté de potimarron" }
        assertEquals(RecipeStatus.HISTORIQUE, velo.status)
        assertEquals(1, velo.timesCooked)
        assertEquals("2026-11-07", velo.lastCookedAt)
        assertEquals("doux", velo.description)
        assertEquals(3, velo.steps.size)

        val gratin = recipes.state.value.first { it.id == favId }
        assertEquals(RecipeStatus.FAVORI, gratin.status) // reste favori
        assertEquals(3, gratin.timesCooked)

        // Gratin pour 5 adultes + 2 enfants = 6 portions => 1,5 kg de pommes de terre
        val merged = ShoppingListMerger.merge(shopping.state.value)
        assertEquals(1.5, merged.first { it.name == "Pommes de terre" }.quantity)
        assertEquals("kg", merged.first { it.name == "Pommes de terre" }.unit)
        // Velouté pour 3 portions avec une recette pour 4 personnes => 750 g de potimarron
        assertEquals(750.0, merged.first { it.name == "Potimarron" }.quantity)

        assertEquals(listOf("Velouté de potimarron", "Gratin dauphinois"), plan.state.value.map { it.recipeTitle })
        assertEquals(Portions(5, 2), plan.state.value[1].portions)
        assertEquals(listOf("Plat impossible"), draft.state.value.map { it.title }) // à réessayer
    }
}

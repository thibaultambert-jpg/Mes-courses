package fr.mescourses.core.menu

import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.MenuDraftRepository
import fr.mescourses.core.repository.RecipeRepository
import fr.mescourses.core.repository.ShoppingRepository
import fr.mescourses.core.shopping.ShoppingListMerger
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max

/** Génère un texte avec le LLM local, sans outils (conversation jetable). */
fun interface TextGenerator {
    suspend fun generate(system: String, prompt: String): String
}

data class ValidationResult(val newRecipes: Int, val reused: Int, val shoppingItems: Int, val failures: List<String>)

/**
 * Valide le menu : génère les recettes manquantes, les range dans le livre (Historique),
 * ajoute les ingrédients (mis à l'échelle des convives) à la liste et crée le menu de la semaine.
 */
class MenuValidator(
    private val generator: TextGenerator,
    private val recipes: RecipeRepository,
    private val shopping: ShoppingRepository,
    private val plan: MealPlanRepository,
    private val draft: MenuDraftRepository,
    private val childFactor: () -> Double,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun validate(onProgress: suspend (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }): ValidationResult {
        val dishes = draft.get()
        val date = today().toString()
        var created = 0
        var reused = 0
        var items = 0
        val failures = mutableListOf<String>()
        val index = CookbookIndex(recipes.all())

        dishes.forEachIndexed { i, dish ->
            onProgress(i, dishes.size, dish.title)
            val existing = dish.recipeId?.let { recipes.get(it) } ?: index.find(dish.title)?.takeUnless { it.banned }
            val recipe = existing ?: generate(dish) ?: run { failures += dish.title; return@forEachIndexed }

            val saved = recipe.copy(
                status = existing?.status ?: RecipeStatus.HISTORIQUE,
                timesCooked = recipe.timesCooked + 1,
                lastCookedAt = date,
                description = recipe.description ?: dish.description.ifBlank { null },
            )
            val id = recipes.save(saved)
            if (existing != null) reused++ else created++

            val list = ShoppingListMerger.fromRecipe(saved, dish.portions.equivalent(childFactor()))
            shopping.add(list)
            items += list.size
            plan.add(PlannedMeal(date = date, recipeId = id, recipeTitle = saved.title, portions = dish.portions))
        }
        onProgress(dishes.size, dishes.size, "")
        // Les plats en échec restent dans le brouillon pour pouvoir réessayer.
        draft.set(dishes.filter { it.title in failures })
        return ValidationResult(created, reused, items, failures)
    }

    /** Recette pour un nombre « rond » de personnes, mise à l'échelle ensuite. */
    private suspend fun generate(dish: MenuDish): Recipe? {
        val servings = max(2, ceil(dish.portions.equivalent(childFactor())).toInt())
        repeat(2) {
            val text = runCatching {
                generator.generate(MenuPrompt.RECIPE_SYSTEM, MenuPrompt.recipeRequest(dish, servings))
            }.getOrNull() ?: return@repeat
            RecipeResponseParser.parse(text, dish.title, servings)?.let { return it.copy(tags = listOf("menu")) }
        }
        return null
    }
}

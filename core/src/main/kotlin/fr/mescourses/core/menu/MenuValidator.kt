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
        // Ingrédients déjà prévus par les plats précédents : proposés aux recettes suivantes.
        val bought = LinkedHashSet<String>()

        dishes.forEachIndexed { i, dish ->
            onProgress(i, dishes.size, dish.title)
            val existing = dish.recipeId?.let { recipes.get(it) } ?: index.find(dish.title)?.takeUnless { it.banned }
            val recipe = existing ?: generate(dish, bought.toList()) ?: run { failures += dish.title; return@forEachIndexed }
            recipe.ingredients.map { it.name.lowercase() }.filter { it.length > 2 }.forEach { bought += it }

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

    /**
     * Recette pour un nombre « rond » de personnes, mise à l'échelle ensuite. Si l'assiette n'est pas
     * complète (protéine, légumes, féculent), on redemande une fois en précisant ce qui manque.
     */
    private suspend fun generate(dish: MenuDish, alreadyBought: List<String>): Recipe? {
        val servings = max(2, ceil(dish.portions.equivalent(childFactor())).toInt())
        var best: Recipe? = null
        var missing = emptyList<PlateBalance.Component>()
        for (attempt in 0 until 3) {
            val text = runCatching {
                generator.generate(MenuPrompt.RECIPE_SYSTEM, MenuPrompt.recipeRequest(dish, servings, alreadyBought.take(25), missing))
            }.getOrNull() ?: continue
            val recipe = RecipeResponseParser.parse(text, dish.title, servings)?.copy(tags = listOf("menu")) ?: continue
            val nowMissing = PlateBalance.missing(listOf(recipe.title) + recipe.ingredients.map { it.name })
            if (nowMissing.isEmpty()) return recipe
            if (best == null || nowMissing.size < missing.size || missing.isEmpty()) best = recipe
            missing = nowMissing
            if (attempt >= 1) break // une seule relance pour l'équilibre
        }
        return best?.copy(
            notes = listOfNotNull(best.notes, "Assiette à compléter : il manque ${missing.joinToString(" et ") { it.missingLabel }}.")
                .joinToString("\n"),
        )
    }
}

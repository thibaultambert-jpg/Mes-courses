package fr.mescourses.core.menu

import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.MenuHistoryEntry
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.MenuDraftRepository
import fr.mescourses.core.repository.MenuHistoryRepository
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
    /** Produits du placard (Réglages) : jamais ajoutés à la liste. */
    private val pantry: () -> List<String> = { fr.mescourses.core.shopping.Pantry.DEFAULTS },
    /** Historique durable des menus (variété des semaines suivantes). */
    private val history: MenuHistoryRepository? = null,
    /** Recette d'un plat venu d'un site, d'après son adresse : déjà lue pendant la recherche, sinon relue sur la page. */
    private val webRecipe: suspend (url: String) -> Recipe? = { null },
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
        val past = mutableListOf<MenuHistoryEntry>()

        dishes.forEachIndexed { i, dish ->
            onProgress(i, dishes.size, dish.title)
            // Dans l'ordre : recette du livre, recette du site (aucune génération), puis recette écrite par le chef.
            val existing = dish.recipeId?.let { recipes.get(it) }
                ?: dish.sourceUrl?.let { index.byUrl(it) }
                ?: index.find(dish.title)?.takeUnless { it.banned || dish.sourceUrl != null }
            val fromSite = if (existing == null) dish.sourceUrl?.let { url -> runCatching { webRecipe(url) }.getOrNull() } else null
            val recipe = existing
                ?: fromSite?.copy(id = 0, tags = (fromSite.tags + "menu").distinct())
                ?: generate(dish, bought.toList())
                ?: run { failures += dish.title; return@forEachIndexed }
            recipe.ingredients.map { it.name.lowercase() }.filter { it.length > 2 }.forEach { bought += it }

            val saved = recipe.copy(
                status = existing?.status ?: RecipeStatus.HISTORIQUE,
                timesCooked = recipe.timesCooked + 1,
                lastCookedAt = date,
                description = recipe.description ?: dish.description.ifBlank { null },
            )
            val id = recipes.save(saved)
            if (existing != null) reused++ else created++

            val list = ShoppingListMerger.fromRecipe(saved, dish.portions.equivalent(childFactor()), pantry())
            shopping.add(list)
            items += list.size
            plan.add(PlannedMeal(date = date, recipeId = id, recipeTitle = saved.title, portions = dish.portions, course = dish.course))
            val names = saved.ingredients.map { it.name }
            past += MenuHistoryEntry(
                date = date,
                title = saved.title,
                sourceUrl = saved.sourceUrl,
                kind = DishKinds.of(saved.title, names).name,
                mainIngredients = names.filterNot { fr.mescourses.core.shopping.Pantry.isStaple(it, pantry()) }
                    .map { IngredientOverlap.key(fr.mescourses.core.ingredients.FoodName.clean(it)) }.filter { it.isNotEmpty() }.distinct(),
            )
        }
        onProgress(dishes.size, dishes.size, "")
        history?.add(past)
        // Les plats en échec restent dans le brouillon pour pouvoir réessayer.
        draft.set(dishes.filter { it.title in failures })
        return ValidationResult(created, reused, items, failures)
    }

    companion object {
        /** Tag des recettes dont seuls les ingrédients ont été générés : les étapes viennent à l'ouverture. */
        const val STEPS_PENDING = "etapes-a-generer"
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
            val recipe = RecipeResponseParser.parse(text, dish.title, servings)
                ?.let { r -> r.copy(tags = if (r.steps.isEmpty()) listOf("menu", STEPS_PENDING) else listOf("menu")) } ?: continue
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

/** Écrit les étapes d'une recette validée dont on n'a généré que les ingrédients. */
class RecipeStepsWriter(private val generator: TextGenerator) {

    fun needsSteps(recipe: Recipe) = recipe.steps.isEmpty() && MenuValidator.STEPS_PENDING in recipe.tags

    /** Renvoie la recette complétée, ou null si le modèle n'a pas répondu correctement (2 essais). */
    suspend fun complete(recipe: Recipe): Recipe? {
        val prompt = MenuPrompt.stepsRequest(recipe.title, recipe.servings, recipe.ingredients.map { it.raw }, recipe.description)
        repeat(2) {
            val text = runCatching { generator.generate(MenuPrompt.RECIPE_SYSTEM, prompt) }.getOrNull() ?: return@repeat
            val steps = RecipeResponseParser.parseSteps(text) ?: return@repeat
            return recipe.copy(
                steps = steps.steps,
                prepMinutes = steps.prepMinutes ?: recipe.prepMinutes,
                cookMinutes = steps.cookMinutes ?: recipe.cookMinutes,
                notes = listOfNotNull(recipe.notes, steps.tip).joinToString("\n").ifBlank { null },
                tags = recipe.tags - MenuValidator.STEPS_PENDING,
            )
        }
        return null
    }
}

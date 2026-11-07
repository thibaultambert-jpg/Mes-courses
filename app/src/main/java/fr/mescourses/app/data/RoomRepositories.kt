package fr.mescourses.app.data

import fr.mescourses.core.model.Ingredient
import fr.mescourses.core.model.ItemSource
import fr.mescourses.core.model.MealSlot
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.RecipeRepository
import fr.mescourses.core.repository.ShoppingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val ingredientsSer = ListSerializer(Ingredient.serializer())
private val stringsSer = ListSerializer(String.serializer())

fun RecipeEntity.toModel() = Recipe(
    id = id,
    title = title,
    servings = servings,
    ingredients = runCatching { json.decodeFromString(ingredientsSer, ingredientsJson) }.getOrDefault(emptyList()),
    steps = runCatching { json.decodeFromString(stringsSer, stepsJson) }.getOrDefault(emptyList()),
    sourceUrl = sourceUrl,
    imageUrl = imageUrl,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    tags = runCatching { json.decodeFromString(stringsSer, tagsJson) }.getOrDefault(emptyList()),
    favorite = favorite,
    notes = notes,
)

fun Recipe.toEntity() = RecipeEntity(
    id = id,
    title = title,
    servings = servings,
    ingredientsJson = json.encodeToString(ingredientsSer, ingredients),
    stepsJson = json.encodeToString(stringsSer, steps),
    sourceUrl = sourceUrl,
    imageUrl = imageUrl,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    tagsJson = json.encodeToString(stringsSer, tags),
    favorite = favorite,
    notes = notes,
)

class RoomRecipeRepository(private val dao: RecipeDao) : RecipeRepository {
    override fun observeAll(): Flow<List<Recipe>> = dao.observeAll().map { l -> l.map { it.toModel() } }
    fun observe(id: Long): Flow<Recipe?> = dao.observe(id).map { it?.toModel() }
    override suspend fun get(id: Long): Recipe? = dao.get(id)?.toModel()

    /** Recherche insensible aux accents dans le titre, les tags et les ingrédients. */
    override suspend fun search(query: String): List<Recipe> {
        val words = query.lowercase().stripAccents().split(' ').filter { it.isNotBlank() }
        val all = dao.all().map { it.toModel() }
        if (words.isEmpty()) return all
        return all.filter { r ->
            val haystack = (r.title + " " + r.tags.joinToString(" ") + " " + r.ingredients.joinToString(" ") { it.name })
                .lowercase().stripAccents()
            words.all { it in haystack }
        }
    }

    override suspend fun save(recipe: Recipe): Long {
        val entity = recipe.toEntity()
        return if (recipe.id == 0L) dao.upsert(entity) else { dao.upsert(entity); recipe.id }
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

private fun PlannedMealEntity.toModel() =
    PlannedMeal(id, date, runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.DINER), recipeId, recipeTitle, servings, addedToList)

private fun PlannedMeal.toEntity() =
    PlannedMealEntity(id, date, slot.name, recipeId, recipeTitle, servings, addedToList)

class RoomMealPlanRepository(private val dao: MealDao) : MealPlanRepository {
    override fun observeFrom(date: String): Flow<List<PlannedMeal>> = dao.observeFrom(date).map { l -> l.map { it.toModel() } }
    override suspend fun between(from: String, to: String) = dao.between(from, to).map { it.toModel() }
    override suspend fun add(meal: PlannedMeal): Long = dao.insert(meal.copy(id = 0).toEntity())
    override suspend fun update(meal: PlannedMeal) = dao.update(meal.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)
}

private fun ShoppingItemEntity.toModel() = ShoppingItem(
    id, name, quantity, unit, runCatching { ItemSource.valueOf(source) }.getOrDefault(ItemSource.MANUEL), origin, checked,
)

private fun ShoppingItem.toEntity() = ShoppingItemEntity(
    id = id, name = name, quantity = quantity, unit = unit, source = source.name, origin = origin, checked = checked,
)

class RoomShoppingRepository(private val dao: ShoppingDao) : ShoppingRepository {
    override fun observeAll(): Flow<List<ShoppingItem>> = dao.observeAll().map { l -> l.map { it.toModel() } }
    override suspend fun all() = dao.all().map { it.toModel() }
    override suspend fun add(items: List<ShoppingItem>) = dao.insertAll(items.map { it.copy(id = 0).toEntity() })
    override suspend fun update(item: ShoppingItem) {
        // conserve la date de création (ordre d'affichage)
        val existing = dao.all().firstOrNull { it.id == item.id }
        dao.update(item.toEntity().copy(createdAt = existing?.createdAt ?: System.currentTimeMillis()))
    }
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun clearChecked() = dao.clearChecked()
}

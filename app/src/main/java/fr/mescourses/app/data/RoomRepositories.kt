package fr.mescourses.app.data

import fr.mescourses.core.model.Ingredient
import fr.mescourses.core.model.ItemSource
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.MenuDraftRepository
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
    status = runCatching { RecipeStatus.valueOf(status) }.getOrDefault(RecipeStatus.HISTORIQUE),
    timesCooked = timesCooked,
    lastCookedAt = lastCookedAt,
    description = description,
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
    status = status.name,
    timesCooked = timesCooked,
    lastCookedAt = lastCookedAt,
    description = description,
    notes = notes,
)

class RoomRecipeRepository(private val dao: RecipeDao) : RecipeRepository {
    override fun observeAll(): Flow<List<Recipe>> = dao.observeAll().map { l -> l.map { it.toModel() } }
    fun observe(id: Long): Flow<Recipe?> = dao.observe(id).map { it?.toModel() }
    override suspend fun all(): List<Recipe> = dao.all().map { it.toModel() }
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
    PlannedMeal(id, date, recipeId, recipeTitle, Portions(adults, children), cooked)

private fun PlannedMeal.toEntity() =
    PlannedMealEntity(id, date, recipeId, recipeTitle, portions.adults, portions.children, cooked)

class RoomMealPlanRepository(private val dao: MealDao) : MealPlanRepository {
    override fun observeAll(): Flow<List<PlannedMeal>> = dao.observeAll().map { l -> l.map { it.toModel() } }
    override suspend fun all() = dao.all().map { it.toModel() }
    override suspend fun add(meal: PlannedMeal): Long = dao.insert(meal.copy(id = 0).toEntity())
    override suspend fun update(meal: PlannedMeal) = dao.update(meal.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun clear() = dao.clear()
}

class RoomMenuDraftRepository(private val dao: MenuDraftDao) : MenuDraftRepository {
    private fun MenuDraftEntity.toModel() = MenuDish(
        title, description, Portions(adults, children), recipeId,
        runCatching { json.decodeFromString(stringsSer, mainIngredients) }.getOrDefault(emptyList()),
    )

    override fun observe(): Flow<List<MenuDish>> = dao.observe().map { l -> l.map { it.toModel() } }
    override suspend fun get(): List<MenuDish> = dao.all().map { it.toModel() }
    override suspend fun set(dishes: List<MenuDish>) = dao.replace(
        dishes.mapIndexed { i, d ->
            MenuDraftEntity(
                i, d.title, d.description, d.portions.adults, d.portions.children, d.recipeId,
                json.encodeToString(stringsSer, d.mainIngredients),
            )
        },
    )
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

/** Mémoire des choix produit (présélection lors des envois suivants au panier). */
class RoomProductMemory(private val dao: IngredientProductDao) : fr.mescourses.core.cart.ProductMemory {
    override suspend fun get(key: String): fr.mescourses.core.carrefour.Product? = dao.get(key)?.let {
        fr.mescourses.core.carrefour.Product(
            id = it.ean, name = it.name, brand = it.brand, price = it.price, identifiers = mapOf("ean" to it.ean),
        )
    }

    override suspend fun remember(key: String, product: fr.mescourses.core.carrefour.Product) =
        dao.upsert(IngredientProductEntity(key, product.ean, product.name, product.brand, product.price))
}

package fr.mescourses.core.repository

import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import kotlinx.coroutines.flow.Flow

/** Livre de recettes. Implémenté avec Room dans l'app, en mémoire dans les tests. */
interface RecipeRepository {
    fun observeAll(): Flow<List<Recipe>>
    suspend fun get(id: Long): Recipe?
    suspend fun search(query: String): List<Recipe>
    suspend fun save(recipe: Recipe): Long
    suspend fun delete(id: Long)
}

interface ShoppingRepository {
    fun observeAll(): Flow<List<ShoppingItem>>
    suspend fun all(): List<ShoppingItem>
    suspend fun add(items: List<ShoppingItem>)
    suspend fun update(item: ShoppingItem)
    suspend fun delete(id: Long)
    suspend fun clearChecked()
}

interface MealPlanRepository {
    fun observeFrom(date: String): Flow<List<PlannedMeal>>
    suspend fun between(from: String, to: String): List<PlannedMeal>
    suspend fun add(meal: PlannedMeal): Long
    suspend fun update(meal: PlannedMeal)
    suspend fun delete(id: Long)
}

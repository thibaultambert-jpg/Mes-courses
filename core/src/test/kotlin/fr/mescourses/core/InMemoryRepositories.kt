package fr.mescourses.core

import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.RecipeRepository
import fr.mescourses.core.repository.ShoppingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryRecipes : RecipeRepository {
    val state = MutableStateFlow<List<Recipe>>(emptyList())
    override fun observeAll(): Flow<List<Recipe>> = state
    override suspend fun get(id: Long) = state.value.firstOrNull { it.id == id }
    override suspend fun search(query: String): List<Recipe> {
        val q = query.lowercase().stripAccents()
        return state.value.filter { q.isBlank() || q in it.title.lowercase().stripAccents() }
    }
    override suspend fun save(recipe: Recipe): Long {
        val id = if (recipe.id != 0L) recipe.id else (state.value.maxOfOrNull { it.id } ?: 0) + 1
        state.value = state.value.filterNot { it.id == id } + recipe.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { state.value = state.value.filterNot { it.id == id } }
}

class InMemoryShopping : ShoppingRepository {
    val state = MutableStateFlow<List<ShoppingItem>>(emptyList())
    private var next = 1L
    override fun observeAll(): Flow<List<ShoppingItem>> = state
    override suspend fun all() = state.value
    override suspend fun add(items: List<ShoppingItem>) { state.value = state.value + items.map { it.copy(id = next++) } }
    override suspend fun update(item: ShoppingItem) { state.value = state.value.map { if (it.id == item.id) item else it } }
    override suspend fun delete(id: Long) { state.value = state.value.filterNot { it.id == id } }
    override suspend fun clearChecked() { state.value = state.value.filterNot { it.checked } }
}

class InMemoryPlan : MealPlanRepository {
    val state = MutableStateFlow<List<PlannedMeal>>(emptyList())
    private var next = 1L
    override fun observeFrom(date: String) = state.map { l -> l.filter { it.date >= date } }
    override suspend fun between(from: String, to: String) = state.value.filter { it.date in from..to }.sortedBy { it.date }
    override suspend fun add(meal: PlannedMeal): Long { val id = next++; state.value = state.value + meal.copy(id = id); return id }
    override suspend fun update(meal: PlannedMeal) { state.value = state.value.map { if (it.id == meal.id) meal else it } }
    override suspend fun delete(id: Long) { state.value = state.value.filterNot { it.id == id } }
}

package fr.mescourses.core

import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.MenuHistoryEntry
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.MenuDraftRepository
import fr.mescourses.core.repository.MenuHistoryRepository
import fr.mescourses.core.repository.RecipeRepository
import fr.mescourses.core.repository.ShoppingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryRecipes : RecipeRepository {
    val state = MutableStateFlow<List<Recipe>>(emptyList())
    override fun observeAll(): Flow<List<Recipe>> = state
    override suspend fun all() = state.value
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
    override fun observeAll(): Flow<List<PlannedMeal>> = state
    override suspend fun all() = state.value
    override suspend fun add(meal: PlannedMeal): Long { val id = next++; state.value = state.value + meal.copy(id = id); return id }
    override suspend fun update(meal: PlannedMeal) { state.value = state.value.map { if (it.id == meal.id) meal else it } }
    override suspend fun delete(id: Long) { state.value = state.value.filterNot { it.id == id } }
    override suspend fun clear() { state.value = emptyList() }
}

class InMemoryDraft : MenuDraftRepository {
    val state = MutableStateFlow<List<MenuDish>>(emptyList())
    override fun observe(): Flow<List<MenuDish>> = state
    override suspend fun get() = state.value
    override suspend fun set(dishes: List<MenuDish>) { state.value = dishes }
}

class InMemoryHistory : MenuHistoryRepository {
    val state = MutableStateFlow<List<MenuHistoryEntry>>(emptyList())
    override suspend fun all() = state.value
    override suspend fun add(entries: List<MenuHistoryEntry>) { state.value = state.value + entries }
}

class InMemoryCatalog(entries: List<fr.mescourses.core.catalog.CatalogEntry> = emptyList()) : fr.mescourses.core.catalog.CatalogStore {
    val entries = LinkedHashMap<String, fr.mescourses.core.catalog.CatalogEntry>().apply { entries.forEach { put(it.url, it) } }
    val empty = HashMap<String, String?>()
    val metas = HashMap<String, String>()

    override suspend fun count() = entries.size
    override suspend fun countBySite() = entries.values.groupingBy { it.siteId }.eachCount()
    override suspend fun search(query: fr.mescourses.core.catalog.CatalogQuery) = entries.values.filter(query::matches)
        .sortedWith(
            if (query.seed == null) compareByDescending<fr.mescourses.core.catalog.CatalogEntry> {
                fr.mescourses.core.recipes.RecipeRating.score(it.rating, it.ratingCount)
            }.thenByDescending { it.ratingCount ?: 0 }
            else compareByDescending<fr.mescourses.core.catalog.CatalogEntry> { fr.mescourses.core.catalog.CatalogPolicy.wellRated(it) }
                .thenByDescending { e -> (e.url.hashCode() * query.seed) % 1000 },
        ).take(query.limit)
    override suspend fun byUrl(url: String) = entries[url]
    override suspend fun upsert(entries: List<fr.mescourses.core.catalog.CatalogEntry>) { entries.forEach { this.entries[it.url] = it } }
    override suspend fun known(siteId: String): Map<String, String?> =
        entries.values.filter { it.siteId == siteId }.associate { it.url to it.lastmod } + empty
    override suspend fun markEmpty(siteId: String, url: String, lastmod: String?) { empty[url] = lastmod }
    override suspend fun meta(key: String) = metas[key]
    override suspend fun setMeta(key: String, value: String) { metas[key] = value }
}

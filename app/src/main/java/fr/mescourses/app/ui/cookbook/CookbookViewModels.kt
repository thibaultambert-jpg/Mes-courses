package fr.mescourses.app.ui.cookbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.core.agent.DateResolver
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.MealSlot
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class CookbookViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val importing = MutableStateFlow(false)
    val messages = MutableStateFlow<String?>(null)

    val recipes: StateFlow<List<Recipe>> = combine(c.recipes.observeAll(), query) { all, q ->
        val words = q.lowercase().stripAccents().split(' ').filter { it.isNotBlank() }
        all.filter { r ->
            val text = (r.title + " " + r.tags.joinToString(" ")).lowercase().stripAccents()
            words.all { it in text }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun import(url: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            importing.value = true
            try {
                val recipe = c.importer.import(url.trim())
                val id = c.recipes.save(recipe)
                messages.value = "« ${recipe.title} » ajoutée au livre de recettes"
                onDone(id)
            } catch (e: Exception) {
                messages.value = "Import impossible : ${e.message}"
            } finally {
                importing.value = false
            }
        }
    }
}

class RecipeDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val recipe: StateFlow<Recipe?> = c.recipes.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val servings = MutableStateFlow<Int?>(null)
    val messages = MutableStateFlow<String?>(null)

    fun servingsOrDefault(r: Recipe) = servings.value ?: c.settings.current.householdSize

    fun addToList(r: Recipe) = viewModelScope.launch {
        val n = servingsOrDefault(r)
        c.shopping.add(ShoppingListMerger.fromRecipe(r, n))
        messages.value = "Ingrédients ajoutés à la liste ($n pers.)"
    }

    fun plan(r: Recipe, date: LocalDate, slot: MealSlot) = viewModelScope.launch {
        c.plan.add(PlannedMeal(date = date.toString(), slot = slot, recipeId = r.id, recipeTitle = r.title, servings = servingsOrDefault(r)))
        messages.value = "Planifié ${DateResolver.label(date)} (${slot.label})"
    }

    fun toggleFavorite(r: Recipe) = viewModelScope.launch { c.recipes.save(r.copy(favorite = !r.favorite)) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.recipes.delete(id); onDone() }
}

class RecipeEditViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val title = MutableStateFlow("")
    val servings = MutableStateFlow("4")
    val ingredients = MutableStateFlow("")
    val steps = MutableStateFlow("")
    val tags = MutableStateFlow("")
    private var original: Recipe? = null

    init {
        if (id != 0L) viewModelScope.launch {
            c.recipes.get(id)?.let { r ->
                original = r
                title.value = r.title
                servings.value = r.servings.toString()
                ingredients.value = r.ingredients.joinToString("\n") { it.raw }
                steps.value = r.steps.joinToString("\n")
                tags.value = r.tags.joinToString(", ")
            }
        }
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        if (title.value.isBlank()) return@launch
        val base = original ?: Recipe(title = "")
        c.recipes.save(
            base.copy(
                title = title.value.trim(),
                servings = servings.value.toIntOrNull()?.coerceIn(1, 50) ?: 4,
                ingredients = IngredientParser.parseAll(ingredients.value.lines()),
                steps = steps.value.lines().map { it.trim() }.filter { it.isNotEmpty() },
                tags = tags.value.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
        )
        onDone()
    }
}

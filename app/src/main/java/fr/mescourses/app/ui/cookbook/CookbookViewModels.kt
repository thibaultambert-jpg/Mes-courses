package fr.mescourses.app.ui.cookbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CookbookViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val tab = MutableStateFlow(RecipeStatus.HISTORIQUE)
    /** Onglet « Découvrir » (recherche sur les sites de recettes) affiché à la place d'une liste du livre. */
    val discover = MutableStateFlow(false)
    private val favorites = fr.mescourses.app.ui.discover.Favorites(c, viewModelScope)
    val importing = MutableStateFlow(false)
    val messages = MutableStateFlow<String?>(null)

    private val all = c.recipes.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Nombre de recettes par liste, pour les onglets. */
    val counts: StateFlow<Map<RecipeStatus, Int>> = combine(all, query) { list, _ -> list.groupingBy { it.status }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val recipes: StateFlow<List<Recipe>> = combine(all, query, tab) { list, q, t ->
        val words = q.lowercase().stripAccents().split(' ').filter { it.isNotBlank() }
        list.filter { it.status == t }.filter { r ->
            val text = (r.title + " " + r.tags.joinToString(" ")).lowercase().stripAccents()
            words.all { it in text }
        }.let { l -> if (t == RecipeStatus.HISTORIQUE) l.sortedByDescending { it.lastCookedAt.orEmpty() } else l.sortedBy { it.title.lowercase() } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun move(recipe: Recipe, status: RecipeStatus) = viewModelScope.launch {
        c.recipes.save(recipe.copy(status = status))
        messages.value = "« ${recipe.title} » → ${status.label}"
    }

    /** Import d'une page web (bouton lien ou « Partager » depuis le navigateur) : va dans les Favoris. */
    fun import(url: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            importing.value = true
            try {
                // Déjà dans le livre : on l'ouvre au lieu d'en créer un doublon.
                (
                    favorites.find(url) ?: c.recipes.all().firstOrNull {
                        it.sourceUrl != null && RecipeSites.normalize(it.sourceUrl!!) == RecipeSites.normalize(url)
                    }
                )?.let { existing ->
                    messages.value = "« ${existing.title} » est déjà dans ton livre"
                    onDone(existing.id)
                    return@launch
                }
                val (id, title) = favorites.addFromUrl(url.trim())
                tab.value = RecipeStatus.FAVORI
                discover.value = false
                messages.value = "« $title » ajoutée aux favoris"
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
    val portions = MutableStateFlow(c.defaultPortions())
    val messages = MutableStateFlow<String?>(null)

    fun childFactor() = c.settings.current.childFactor

    /** Étapes de la recette : écrites par le chef en tâche de fond, une recette à la fois (file d'attente). */
    sealed interface StepsState {
        data object Idle : StepsState
        data object Writing : StepsState
        data class Queued(val position: Int) : StepsState
        data class Failed(val message: String) : StepsState
    }

    val steps: StateFlow<StepsState> = combine(c.chef.writing, c.chef.stepsQueue, c.chef.stepsFailures) { writing, queue, failures ->
        when {
            writing == id -> StepsState.Writing
            id in queue -> StepsState.Queued(queue.filter { it != writing }.indexOf(id) + 1)
            failures[id] != null -> StepsState.Failed(failures.getValue(id))
            else -> StepsState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StepsState.Idle)
    val model = c.model.status
    private val writer = c.stepsWriter()

    init {
        viewModelScope.launch {
            // Recette ouverte sans étapes : elle passe en tête de la file du chef.
            val r = recipe.filterNotNull().first()
            if (writer.needsSteps(r)) c.chef.requestSteps(id, first = true)
        }
    }

    fun writeSteps() = c.chef.requestSteps(id, first = true)

    fun addToList(r: Recipe) = viewModelScope.launch {
        val p = portions.value
        c.shopping.add(ShoppingListMerger.fromRecipe(r, p.equivalent(childFactor())))
        messages.value = "Ingrédients ajoutés à la liste (${p.label()})"
    }

    /** Ajoute la recette au menu en cours de préparation (onglet Menu). */
    fun addToMenu(r: Recipe) = viewModelScope.launch {
        c.draft.set(c.draft.get() + MenuDish(r.title, r.description.orEmpty(), portions.value, r.id))
        messages.value = "« ${r.title} » ajouté au menu en préparation"
    }

    fun move(r: Recipe, status: RecipeStatus) = viewModelScope.launch {
        c.recipes.save(r.copy(status = status))
        messages.value = "Déplacée vers « ${status.label} »"
    }

    /** Envoie les ingrédients (mis à l'échelle) à l'écran Panier Carrefour. */
    fun sendToCart(r: Recipe) {
        c.pendingCart.value = fr.mescourses.app.PendingCart(
            label = r.title,
            items = ShoppingListMerger.fromRecipe(r, portions.value.equivalent(childFactor())),
        )
    }

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
        // Une recette écrite à la main va dans les Favoris.
        val base = original ?: Recipe(title = "", status = RecipeStatus.FAVORI)
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

/** Portions d'une fiche recette. */
fun Portions.withAdults(delta: Int) = copy(adults = (adults + delta).coerceAtLeast(0))
fun Portions.withChildren(delta: Int) = copy(children = (children + delta).coerceAtLeast(0))

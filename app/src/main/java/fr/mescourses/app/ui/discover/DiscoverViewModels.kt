package fr.mescourses.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeJsonLdExtractor
import fr.mescourses.core.recipes.RecipeSearchResult
import fr.mescourses.core.recipes.RecipeSite
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.season.Seasonality
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Recettes du livre indexées par adresse d'origine, et ajout aux favoris sans doublon. */
class Favorites(private val c: AppContainer, scope: kotlinx.coroutines.CoroutineScope) {
    /** Adresse normalisée → recette du livre (toutes listes confondues). */
    val byUrl: StateFlow<Map<String, Recipe>> = c.recipes.observeAll()
        .map { list -> list.filter { it.sourceUrl != null }.associateBy { RecipeSites.normalize(it.sourceUrl!!) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun find(url: String): Recipe? = byUrl.value[RecipeSites.normalize(url)]

    /** Range la recette dans les favoris ; si elle est déjà dans le livre, la déplace au lieu de la dupliquer. */
    suspend fun add(recipe: Recipe): Long {
        val existing = recipe.sourceUrl?.let { find(it) } ?: c.recipes.all().firstOrNull {
            it.sourceUrl != null && recipe.sourceUrl != null && RecipeSites.normalize(it.sourceUrl!!) == RecipeSites.normalize(recipe.sourceUrl!!)
        }
        if (existing != null) {
            if (existing.status != RecipeStatus.FAVORI) c.recipes.save(existing.copy(status = RecipeStatus.FAVORI))
            return existing.id
        }
        return c.recipes.save(recipe.copy(id = 0, status = RecipeStatus.FAVORI))
    }

    /** Importe la page (si besoin) puis l'ajoute aux favoris. */
    suspend fun addFromUrl(url: String): Pair<Long, String> {
        find(url)?.let { return add(it) to it.title }
        val recipe = c.importer.import(url.trim())
        return add(recipe) to recipe.title
    }
}

/** Onglet « Découvrir » : recherche sur les sites de recettes. */
class DiscoverViewModel(private val c: AppContainer) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Done(val result: RecipeSearchResult) : State
    }

    enum class Filter(val label: String, val words: String) {
        VEGETARIEN("Végétarien", "végétarien"),
        RAPIDE("Rapide", "rapide"),
        LEGER("Léger", "léger"),
    }

    val favorites = Favorites(c, viewModelScope)
    val query = MutableStateFlow("")
    /** Sites choisis ; vide = tous les sites avec recherche. */
    val sites = MutableStateFlow<Set<String>>(emptySet())
    val filters = MutableStateFlow<Set<Filter>>(emptySet())
    val state = MutableStateFlow<State>(State.Idle)
    /** Adresses en cours d'ajout aux favoris (étoile qui tourne). */
    val adding = MutableStateFlow<Set<String>>(emptySet())
    val messages = MutableStateFlow<String?>(null)
    private var lastSearch: String? = null
    /** Mode « Populaires du moment » (au lieu d'une recherche). */
    val popularMode = MutableStateFlow(false)

    val seasonIdeas: List<String> = Seasonality.highlights(LocalDate.now().month).take(10)
    val searchableSites: List<RecipeSite> = RecipeSites.searchable
    val otherSites: List<RecipeSite> = RecipeSites.all.filterNot { it.searchable }

    private var job: Job? = null

    fun toggleSite(id: String) {
        sites.value = if (id in sites.value) sites.value - id else sites.value + id
        refresh()
    }

    fun allSites() {
        sites.value = emptySet()
        refresh()
    }

    private fun refresh() {
        if (popularMode.value) popular() else if (lastSearch != null) search()
    }

    /** Recettes populaires du moment sur les sites choisis. */
    fun popular() {
        popularMode.value = true
        val chosen = searchableSites.filter { sites.value.isEmpty() || it.id in sites.value }
        job?.cancel()
        job = viewModelScope.launch {
            state.value = State.Loading
            state.value = State.Done(c.recipeSearch.popular(chosen))
        }
    }

    fun togglePopular() {
        if (popularMode.value) {
            popularMode.value = false
            if (lastSearch != null) search() else { job?.cancel(); state.value = State.Idle }
        } else {
            popular()
        }
    }

    fun toggleFilter(f: Filter) {
        filters.value = if (f in filters.value) filters.value - f else filters.value + f
        if (lastSearch != null) search()
    }

    fun searchIdea(idea: String) {
        query.value = idea
        search()
    }

    fun search() {
        val q = (listOf(query.value.trim()) + filters.value.map { it.words }).filter { it.isNotBlank() }.joinToString(" ")
        if (query.value.isBlank()) return
        popularMode.value = false
        lastSearch = q
        val chosen = searchableSites.filter { sites.value.isEmpty() || it.id in sites.value }
        job?.cancel()
        job = viewModelScope.launch {
            state.value = State.Loading
            state.value = State.Done(c.recipeSearch.search(q, chosen))
        }
    }

    /** Adresse de recherche d'un site, pour l'ouvrir dans le navigateur de l'app. */
    fun searchUrl(site: RecipeSite): String =
        if (popularMode.value) site.popularPage else site.searchUrlFor(lastSearch ?: query.value) ?: site.homeUrl

    fun addFavorite(hit: RecipeHit) {
        val existing = favorites.find(hit.url)
        if (existing?.status == RecipeStatus.FAVORI) {
            messages.value = "« ${existing.title} » est déjà dans tes favoris"
            return
        }
        if (hit.url in adding.value) return
        viewModelScope.launch {
            adding.value = adding.value + hit.url
            try {
                val (_, title) = favorites.addFromUrl(hit.url)
                messages.value = "⭐ « $title » ajoutée aux favoris"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.value = "Ajout impossible : ${e.message}"
            } finally {
                adding.value = adding.value - hit.url
            }
        }
    }
}

/** Aperçu d'une recette d'un site, avant de l'ajouter au livre. */
class RecipePreviewViewModel(private val c: AppContainer, val url: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val recipe: Recipe) : State
        data class Failed(val message: String) : State
    }

    val favorites = Favorites(c, viewModelScope)
    val state = MutableStateFlow<State>(State.Loading)
    val messages = MutableStateFlow<String?>(null)
    val site: RecipeSite? = RecipeSites.forUrl(url)

    init { load() }

    fun load() = viewModelScope.launch {
        state.value = State.Loading
        state.value = try {
            State.Loaded(favorites.find(url) ?: c.importer.import(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            State.Failed(e.message ?: "La page n'a pas pu être lue.")
        }
    }

    fun addFavorite(r: Recipe, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = favorites.add(r)
        messages.value = "⭐ Ajoutée aux favoris"
        onSaved(id)
    }

    /** Ajoute au menu en préparation (la recette rejoint aussi les favoris, pour garder ses ingrédients). */
    fun addToMenu(r: Recipe) = viewModelScope.launch {
        val id = favorites.add(r)
        c.draft.set(c.draft.get() + MenuDish(r.title, r.description.orEmpty(), c.defaultPortions(), id))
        messages.value = "« ${r.title} » ajoutée au menu en préparation (et aux favoris)"
    }

    fun addToList(r: Recipe) = viewModelScope.launch {
        val p = c.defaultPortions()
        c.shopping.add(ShoppingListMerger.fromRecipe(r, p.equivalent(c.settings.current.childFactor)))
        messages.value = "Ingrédients ajoutés à la liste (${p.label()})"
    }
}

/** Navigateur intégré : détecte la recette de la page affichée. */
class RecipeBrowserViewModel(private val c: AppContainer) : ViewModel() {
    val favorites = Favorites(c, viewModelScope)
    /** Recette lue sur la page courante (null = pas de recette sur cette page). */
    val detected = MutableStateFlow<Recipe?>(null)
    val messages = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)

    /** Appelé avec le contenu des balises JSON-LD de la page. */
    fun onPage(url: String, jsonLdBlocks: List<String>) {
        val recipe = RecipeJsonLdExtractor.extractFromJsonLd(jsonLdBlocks, url)
        // Garde la recette si un nouvel appel (même page) ne la retrouve pas encore.
        if (recipe != null || detected.value?.sourceUrl != url) detected.value = recipe
    }

    fun clear() { detected.value = null }

    fun add(r: Recipe, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        saving.value = true
        try {
            val id = favorites.add(r)
            messages.value = "⭐ « ${r.title} » ajoutée aux favoris"
            onSaved(id)
        } finally {
            saving.value = false
        }
    }
}

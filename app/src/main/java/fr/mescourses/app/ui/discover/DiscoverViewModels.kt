package fr.mescourses.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.core.catalog.CatalogQuery
import fr.mescourses.core.catalog.CatalogRanking
import fr.mescourses.core.catalog.toHit
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.recipes.QuickIdea
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeFilters
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeSort
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

    val favorites = Favorites(c, viewModelScope)
    val query = MutableStateFlow("")
    /** Sites choisis ; vide = tous les sites. */
    val sites = MutableStateFlow<Set<String>>(emptySet())
    /** Filtres actifs (type de plat, ingrédient principal, régime, temps, envie). */
    val filters = MutableStateFlow<Set<RecipeFilter>>(emptySet())
    val sort = MutableStateFlow(RecipeSort.PERTINENCE)
    val state = MutableStateFlow<State>(State.Idle)
    /** Adresses en cours d'ajout aux favoris (étoile qui tourne). */
    val adding = MutableStateFlow<Set<String>>(emptySet())
    val messages = MutableStateFlow<String?>(null)
    /** Mode « Populaires du moment » (au lieu d'une recherche). */
    val popularMode = MutableStateFlow(false)
    /** Les résultats affichés viennent du catalogue local ; les sites n'ont pas (encore) été interrogés. */
    val localOnly = MutableStateFlow(false)
    /** Recherche complémentaire sur les sites en cours. */
    val searchingOnline = MutableStateFlow(false)
    /** Résultats bruts des sites, avant filtres et tri de l'app (pour changer le tri sans rechercher). */
    private var raw: RecipeSearchResult? = null
    /** Tirage du classement « Pertinence » dans le catalogue : change à chaque recherche et à chaque rechargement. */
    private var seed = newSeed()
    /** Rechargement demandé par l'utilisateur en cours (geste « tirer vers le bas »). */
    val reloading = MutableStateFlow(false)

    private fun newSeed() = kotlin.random.Random.nextInt(1, 1_000_000)

    val seasonIdeas: List<String> = Seasonality.highlights(LocalDate.now().month).take(10)
    val quickIdeas: List<QuickIdea> = RecipeFilters.quickIdeas
    val searchableSites: List<RecipeSite> = RecipeSites.searchable
    /** Tous les sites connus, proposés dans les filtres (catalogue local ; recherche en ligne pour ceux qui l'ont). */
    val allSitesList: List<RecipeSite> = RecipeSites.all
    /** Nombre de recettes du catalogue local par site, affiché dans les filtres. */
    val catalogStatus: StateFlow<fr.mescourses.app.data.CatalogStatus> = c.catalogSync.status
    val catalogCounts: StateFlow<Map<String, Int>> = c.catalogSync.status
        .map { it.bySite }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Ce site a une base de recettes téléchargeable (sinon il ne se cherche qu'en ligne). */
    fun hasCatalog(siteId: String) = fr.mescourses.core.catalog.CatalogSources.byId(siteId) != null

    init {
        // Une base supprimée ne reste pas cochée dans les filtres.
        viewModelScope.launch {
            c.catalogSync.status.collect { st ->
                if (st.loaded) sites.value = sites.value.filter { !hasCatalog(it) || it in st.installed }.toSet()
            }
        }
    }

    /** Sites à interroger en ligne : ceux choisis, sinon tous ceux qui ont une recherche. */
    private fun chosenSites(): List<RecipeSite> =
        if (sites.value.isEmpty()) searchableSites else allSitesList.filter { it.id in sites.value }

    private var job: Job? = null

    fun toggleSite(id: String) {
        sites.value = if (id in sites.value) sites.value - id else sites.value + id
    }

    fun allSites() {
        sites.value = emptySet()
    }

    /** Relance la recherche en cours (après un changement dans la feuille de filtres). */
    fun refresh() {
        if (popularMode.value) popular() else search()
    }

    /** Bouton « Nouvelle liste » ou geste « tirer vers le bas » : relance pour obtenir d'autres recettes. */
    fun reload() {
        if (state.value == State.Idle && !popularMode.value) return
        reloading.value = true
        refresh()
        val running = job
        viewModelScope.launch {
            running?.join()
            reloading.value = false
        }
    }

    /**
     * Retour arrière dans Découvrir : des résultats affichés reviennent à l'accueil de Découvrir.
     * Renvoie false si on est déjà sur l'accueil (le retour sort alors de Découvrir).
     */
    fun back(): Boolean {
        if (state.value == State.Idle && !popularMode.value) return false
        job?.cancel()
        popularMode.value = false
        localOnly.value = false
        searchingOnline.value = false
        state.value = State.Idle
        return true
    }

    /** Recettes populaires du moment sur les sites choisis (filtres et tri appliqués par l'app). */
    fun popular() {
        popularMode.value = true
        localOnly.value = false
        val chosen = chosenSites()
        job?.cancel()
        job = viewModelScope.launch {
            state.value = State.Loading
            show(c.recipeSearch.popular(chosen))
        }
    }

    fun togglePopular() {
        if (popularMode.value) {
            popularMode.value = false
            search()
        } else {
            popular()
        }
    }

    /** Tuile ou puce : active / retire un filtre, puis relance (les filtres seuls suffisent pour chercher). */
    fun toggleFilter(f: RecipeFilter) {
        filters.value = RecipeFilters.toggle(filters.value, f)
        refresh()
    }

    /** Dans la feuille de filtres : on change sans relancer à chaque toucher (bouton « Voir les recettes »). */
    fun toggleFilterQuietly(f: RecipeFilter) {
        filters.value = RecipeFilters.toggle(filters.value, f)
    }

    fun clearFilters() {
        filters.value = emptySet()
        refresh()
    }

    fun setSort(s: RecipeSort) {
        val changed = sort.value != s
        sort.value = s
        // Dans le catalogue, « Pertinence » est un tirage et les autres tris lisent les mieux notées : on relit.
        if (changed && localOnly.value && state.value is State.Done) { search(); return }
        raw?.let { r -> if (state.value is State.Done) state.value = State.Done(filtered(r)) }
    }

    fun applyIdea(idea: QuickIdea) {
        query.value = ""
        filters.value = idea.filters
        popularMode.value = false
        search()
    }

    fun searchIdea(idea: String) {
        query.value = idea
        search()
    }

    fun search() {
        val q = RecipeFilters.query(query.value, filters.value)
        popularMode.value = false
        if (q.isBlank()) {
            job?.cancel()
            state.value = State.Idle
            return
        }
        val chosen = chosenSites()
        job?.cancel()
        searchingOnline.value = false
        seed = newSeed()
        val draw = seed.takeIf { sort.value == RecipeSort.PERTINENCE }
        job = viewModelScope.launch {
            state.value = State.Loading
            // Le catalogue local d'abord (immédiat, sans réseau) ; les sites seulement s'il est vide.
            val local = runCatching {
                if (c.catalog.count() == 0) null
                else CatalogRanking.search(c.catalog, CatalogQuery.of(query.value, filters.value, LocalDate.now().monthValue, sites.value), draw).map { it.toHit() }
            }.getOrNull()
            localOnly.value = local != null
            show(if (local != null) RecipeSearchResult(local, emptyMap(), emptyList()) else c.recipeSearch.search(q, chosen))
        }
    }

    /** « Chercher aussi sur les sites » : complète les résultats du catalogue par une recherche en ligne. */
    fun searchOnline() {
        val q = RecipeFilters.query(query.value, filters.value)
        val current = raw ?: return
        if (q.isBlank() || searchingOnline.value) return
        val chosen = chosenSites()
        job?.cancel()
        job = viewModelScope.launch {
            searchingOnline.value = true
            try {
                val online = c.recipeSearch.search(q, chosen)
                localOnly.value = false
                show(online.copy(hits = (current.hits + online.hits).distinctBy { RecipeSites.normalize(it.url) }))
            } finally {
                searchingOnline.value = false
            }
        }
    }

    /** Adresses (normalisées) des recettes en favoris : elles n'apparaissent plus dans les résultats. */
    private var favoriteUrls: Set<String> = emptySet()

    private fun filtered(r: RecipeSearchResult) =
        r.copy(hits = RecipeFilters.apply(r.hits.filterNot { RecipeSites.normalize(it.url) in favoriteUrls }, filters.value, sort.value))

    private suspend fun show(r: RecipeSearchResult) {
        favoriteUrls = c.recipes.all().filter { it.favorite && it.sourceUrl != null }.map { RecipeSites.normalize(it.sourceUrl!!) }.toSet()
        raw = r
        state.value = State.Done(filtered(r))
    }

    /** Adresse de recherche d'un site, pour l'ouvrir dans le navigateur de l'app. */
    fun searchUrl(site: RecipeSite): String =
        if (popularMode.value) site.popularPage else site.searchUrlFor(RecipeFilters.query(query.value, filters.value).ifBlank { query.value }) ?: site.homeUrl

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

    /** Vrai quand la fiche vient de ce qu'on savait déjà (hors ligne, ou site injoignable). */
    val offline = MutableStateFlow(false)

    /** Nombre de personnes pour cette recette : celui des Réglages au départ, modifiable ici. */
    private val fromCard: Portions? = c.takePortionHint(null, url)
    val portions = MutableStateFlow(fromCard ?: c.defaultPortions())

    /** Change les personnes ; si l'on vient d'une carte du menu, elle suit. */
    fun setPortions(p: Portions) {
        portions.value = p
        if (fromCard != null) viewModelScope.launch { c.syncPortions(null, url, p) }
    }

    fun childFactor() = c.settings.current.childFactor

    /** État de l'assistant (le bouton « générer » n'a de sens que s'il est installé). */
    val chefStatus = c.model.status

    init { load() }

    fun load() = viewModelScope.launch {
        state.value = State.Loading
        offline.value = false
        favorites.find(url)?.let { state.value = State.Loaded(it); return@launch }
        // Sans internet, inutile d'attendre le site : on affiche tout de suite ce qui est déjà connu.
        if (!c.online()) c.knownRecipe(url)?.let { offline.value = true; state.value = State.Loaded(it); return@launch }
        state.value = try {
            State.Loaded(c.importer.import(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val known = c.knownRecipe(url)
            if (known != null) { offline.value = true; State.Loaded(known) }
            else State.Failed(e.message ?: "La page n'a pas pu être lue.")
        }
    }

    fun addFavorite(r: Recipe, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = favorites.add(r)
        messages.value = "⭐ Ajoutée aux favoris"
        onSaved(id)
    }

    /**
     * Ajoute au menu en préparation, sans la mettre en favoris : le plat garde l'adresse du site, et la recette
     * n'entre dans le livre (historique) qu'à la validation du menu. Déjà dans le livre : on la réutilise.
     */
    fun addToMenu(r: Recipe) = viewModelScope.launch {
        val inBook = favorites.find(url) ?: r.sourceUrl?.let { favorites.find(it) }
        val minutes = listOfNotNull(r.prepMinutes, r.cookMinutes).takeIf { it.isNotEmpty() }?.sum()
        c.draft.set(
            c.draft.get() + MenuDish(
                title = r.title,
                description = r.description.orEmpty(),
                portions = portions.value,
                recipeId = inBook?.id,
                mainIngredients = r.ingredients.map { it.name }.take(8),
                sourceUrl = r.sourceUrl ?: url,
                imageUrl = r.imageUrl,
                totalMinutes = minutes,
            ),
        )
        messages.value = "« ${r.title} » ajoutée au menu en préparation"
    }

    fun addToList(r: Recipe) = viewModelScope.launch {
        val p = portions.value
        c.shopping.add(ShoppingListMerger.fromRecipe(r, p.equivalent(c.settings.current.childFactor), c.settings.current.pantryList()))
        messages.value = "Ingrédients ajoutés à la liste (${p.label()})"
    }

    /**
     * Le chef complète une recette sans étapes. Elle doit être dans le livre pour recevoir ses étapes : on l'y range
     * (favoris), on le lance sur demande seulement, puis [onSaved] ouvre la fiche du livre où l'on suit l'écriture.
     */
    fun generateSteps(r: Recipe, onSaved: (Long) -> Unit) = viewModelScope.launch {
        val id = favorites.add(r.copy(tags = (r.tags + MenuValidator.STEPS_PENDING).distinct()))
        val saved = c.recipes.get(id)
        if (saved != null && saved.steps.isEmpty() && MenuValidator.STEPS_PENDING !in saved.tags) c.recipes.save(saved.copy(tags = saved.tags + MenuValidator.STEPS_PENDING))
        c.chef.requestSteps(id, first = true)
        onSaved(id)
    }
}

/** Navigateur intégré : détecte la recette de la page affichée. */
class RecipeBrowserViewModel(private val c: AppContainer) : ViewModel() {
    val favorites = Favorites(c, viewModelScope)
    /** Recette lue sur la page courante (null = pas de recette sur cette page). */
    val detected = MutableStateFlow<Recipe?>(null)
    val messages = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)

    /**
     * Historique et page courante du navigateur, gardés pendant que la version simplifiée d'une recette
     * est affichée (la WebView est alors détruite) : au retour, on reprend exactement là où on était.
     */
    var webState: android.os.Bundle? = null

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

package fr.mescourses.core.menu

import fr.mescourses.core.catalog.CatalogQuery
import fr.mescourses.core.catalog.CatalogStore
import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeFilters
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeImporter
import fr.mescourses.core.recipes.RecipeSearcher
import fr.mescourses.core.recipes.RecipeSite
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.repository.MenuHistoryRepository
import fr.mescourses.core.repository.RecipeRepository
import fr.mescourses.core.season.Seasonality
import fr.mescourses.core.shopping.Pantry
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.temporal.ChronoField

/**
 * Une vraie recette (lue sur un site ou tirée du livre) qui peut entrer au menu, avec ce qu'il faut pour la
 * comparer aux autres : saison, type de plat, ingrédients à acheter.
 */
data class Candidate(
    val recipe: Recipe,
    val kind: DishKind,
    /** Produits vraiment de saison de la recette, et ceux qui ne le sont pas ce mois-ci. */
    val seasonal: List<String>,
    val outOfSeason: List<String>,
    /** Clés des ingrédients à acheter (hors placard), pour mesurer ce que les plats partagent. */
    val keys: Set<String>,
    /** Les mêmes, lisibles, dans l'ordre de la recette (8 au plus). */
    val mainIngredients: List<String>,
    val missing: List<PlateBalance.Component>,
    val protein: String?,
    val fromCookbook: Boolean,
    /** Note propre à la recette, indépendante du reste du menu. */
    val base: Double,
) {
    /** Forme du plat (pâtes, tarte, gratin…), pour ne pas en mettre trois au menu. */
    val form: String? get() = DishForms.of(recipe.title)
    val site: String? get() = siteName
    val minutes: Int? get() = listOfNotNull(recipe.prepMinutes, recipe.cookMinutes).takeIf { it.isNotEmpty() }?.sum()
    val siteName: String? get() = recipe.sourceUrl?.let { RecipeSites.forUrl(it)?.name }

    fun toDish(portions: Portions, course: fr.mescourses.core.catalog.Course? = null) = MenuDish(
        title = recipe.title,
        description = recipe.description.orEmpty(),
        portions = portions,
        recipeId = recipe.id.takeIf { it != 0L },
        mainIngredients = mainIngredients,
        sourceUrl = recipe.sourceUrl,
        imageUrl = recipe.imageUrl,
        totalMinutes = minutes,
        course = course,
    )

    /** Ligne du prompt : « 3. Gratin de chou-fleur [viande · 35 min · saison : chou-fleur] — chou-fleur, jambon, lait ». */
    fun line(number: Int): String {
        val facts = listOfNotNull(
            kind.label,
            minutes?.let { "$it min" },
            seasonal.takeIf { it.isNotEmpty() }?.let { "saison : ${it.joinToString(", ")}" },
            outOfSeason.takeIf { it.isNotEmpty() }?.let { "hors saison : ${it.joinToString(", ")}" },
            if (fromCookbook) (if (recipe.favorite) "favori" else "déjà cuisiné") else null,
        )
        return "$number. ${recipe.title} [${facts.joinToString(" · ")}] — ${mainIngredients.take(4).joinToString(", ")}"
    }

    companion object {
        fun of(recipe: Recipe, wishes: MenuWishes, history: MenuHistory, today: LocalDate, pantry: List<String>, fromCookbook: Boolean, rating: Double? = null): Candidate {
            val bought = recipe.ingredients.map { FoodName.clean(it.name) }.filter { it.isNotBlank() && !Pantry.isStaple(it, pantry) }
            val keys = bought.map(IngredientOverlap::key).filter { it.isNotEmpty() }.toSet()
            val names = recipe.ingredients.map { it.name }
            val season = Seasonality.report(listOf(recipe.title) + names, today.month)
            val missing = PlateBalance.missing(listOf(recipe.title) + names)
            val useUp = wishes.useUp.map(IngredientOverlap::key).filter { it.isNotEmpty() }
                .count { u -> keys.any { it == u || " $u " in " $it " } }
            val base = min(season.seasonal.size, 4) * 2.0 -
                season.outOfSeason.size * 4.0 +
                (if (missing.isEmpty()) 2.0 else -2.0 * missing.size) +
                useUp * 3.0 -
                history.repetition(keys) +
                (rating?.let { (it - 3.5).coerceIn(-1.0, 1.5) } ?: 0.0) +
                (if (recipe.favorite) 1.0 else 0.0)
            return Candidate(
                recipe = recipe,
                kind = DishKinds.of(recipe.title, names),
                seasonal = season.seasonal,
                outOfSeason = season.outOfSeason,
                keys = keys,
                mainIngredients = bought.distinctBy(IngredientOverlap::key).take(8),
                missing = missing,
                protein = PlateBalance.protein(listOf(recipe.title) + names),
                fromCookbook = fromCookbook,
                base = base,
            )
        }

        private fun min(a: Int, b: Int) = if (a < b) a else b
    }
}

/** Candidates de la session, numérotées à partir de 1 : le LLM désigne une recette par son numéro. */
class CandidatePool {
    private val items = ArrayList<Candidate>()

    @Synchronized fun set(list: List<Candidate>) { items.clear(); add(list) }

    /** Ajoute les candidates nouvelles (même adresse ou même titre = déjà là) et renvoie leurs numéros. */
    @Synchronized fun add(list: List<Candidate>): List<Int> = list.map { c ->
        val existing = items.indexOfFirst { same(it, c) }
        if (existing >= 0) existing + 1 else { items += c; items.size }
    }

    @Synchronized fun all(): List<Candidate> = items.toList()
    @Synchronized fun get(number: Int): Candidate? = items.getOrNull(number - 1)
    @Synchronized fun numberOf(c: Candidate): Int = items.indexOf(c) + 1
    val size: Int @Synchronized get() = items.size

    @Synchronized fun byUrl(url: String): Candidate? {
        val key = RecipeSites.normalize(url)
        return items.firstOrNull { it.recipe.sourceUrl?.let(RecipeSites::normalize) == key }
    }

    /** La candidate d'où vient un plat du menu, s'il en vient. */
    fun of(dish: MenuDish): Candidate? = dish.sourceUrl?.let(::byUrl)
        ?: all().firstOrNull { (dish.recipeId != null && it.recipe.id == dish.recipeId) || it.recipe.title == dish.title }

    private fun same(a: Candidate, b: Candidate): Boolean {
        val ua = a.recipe.sourceUrl?.let(RecipeSites::normalize)
        val ub = b.recipe.sourceUrl?.let(RecipeSites::normalize)
        return (ua != null && ua == ub) || ShoppingListMerger.key(a.recipe.title) == ShoppingListMerger.key(b.recipe.title)
    }
}

data class FindResult(
    /** Les meilleures d'abord (note propre). */
    val candidates: List<Candidate>,
    val queries: List<String>,
    /** Sites qui n'ont répondu à aucune recherche. */
    val failedSites: List<String>,
)

/**
 * Cherche les recettes du menu sur les sites de « Découvrir » : recherches bâties sur les légumes de saison et les
 * envies, tri des résultats (bannis, déjà mangés, trop longs), puis lecture des vraies fiches (ingrédients, durées).
 * Aucun LLM ici : le modèle choisit ensuite parmi ces candidates.
 */
class CandidateFinder(
    private val searcher: RecipeSearcher,
    private val importer: RecipeImporter,
    private val recipes: RecipeRepository,
    private val history: MenuHistoryRepository,
    private val pantry: () -> List<String> = { Pantry.DEFAULTS },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val sites: List<RecipeSite> = RecipeSites.searchable,
    /** Catalogue local : s'il contient des recettes, le menu se compose sans réseau. */
    private val catalog: CatalogStore? = null,
) {
    companion object {
        const val MAX_IMPORTS = 24
        private const val PER_SITE = 8
        /** Catalogue : fiches demandées par recherche, et candidates gardées pour chacune. */
        private const val LOCAL_FETCH = 40
        private const val LOCAL_KEEP = 6
        /** Par recherche, pas plus de tant de recettes d'un même site : sinon le mieux noté occupe tout le menu. */
        private const val LOCAL_PER_SITE = 2

        private fun perSite(list: List<Candidate>, max: Int): List<Candidate> {
            val seen = HashMap<String?, Int>()
            return list.filter { c -> (seen.merge(c.siteName, 1, Int::plus) ?: 1) <= max }
        }
        private val meats = listOf("poulet", "porc", "dinde")
        private val stopWords = setOf(
            "un", "une", "des", "le", "la", "les", "de", "du", "au", "aux", "et", "ou", "avec", "pour", "sans", "en", "ce", "cette",
            "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "soir", "midi", "semaine", "weekend",
            "je", "on", "veux", "voudrais", "aimerais", "envie", "plutot", "plus", "moins", "tres", "bien", "plat", "plats", "repas",
        )

        /** Envies proposées en un toucher, avec les mots envoyés aux sites. */
        val QUICK_WISHES = linkedMapOf(
            "Petit budget" to "économique",
            "Les enfants aiment" to "enfant",
            "Léger" to "léger",
            "Réconfortant" to "réconfortant",
            "Cuisine du monde" to "cuisine du monde",
            "Batch cooking" to "batch cooking",
        )

        /** Mots utiles d'une envie libre : « une raclette samedi » → « raclette ». */
        fun wishQuery(wish: String): String? {
            QUICK_WISHES.entries.firstOrNull { it.key.equals(wish.trim(), ignoreCase = true) }?.let { return it.value }
            val words = wish.lowercase().replace(Regex("[^\\p{L}\\p{N} '-]"), " ").split(' ', '\'')
                .filter { it.length > 1 && it.stripAccents() !in stopWords }
            return words.takeIf { it.size in 1..4 }?.joinToString(" ")
        }
    }

    /** Une recherche de la semaine : le texte envoyé aux sites, et la même en critères pour le catalogue. */
    data class Spec(val text: String, val words: String, val kind: DishKind? = null, val seasonal: Boolean = false)

    /**
     * Recherches de la semaine. Les légumes de saison tournent d'une semaine à l'autre (et ceux de la semaine
     * dernière passent en dernier) ; chaque recherche vise un type de plat selon la composition demandée.
     */
    fun specs(wishes: MenuWishes, history: MenuHistory, date: LocalDate = today(), round: Int = 0): List<Spec> {
        val w = wishes.normalized()
        val vegetables = Seasonality.seasonalVegetables(date.month).ifEmpty { Seasonality.forMonth(date.month).vegetables }.sorted()
        val shift = (date.get(ChronoField.ALIGNED_WEEK_OF_YEAR) * 3 + round * 5) % vegetables.size
        val rotated = (vegetables.drop(shift) + vegetables.take(shift)).sortedBy { history.usedLastWeek(it) }

        val slots: List<DishKind?> = DishKind.entries.flatMap { k -> List(w.quota(k)) { k } } + List(w.meals - w.vegetarian - w.fish - w.meat) { null }
        val count = (w.meals + 1).coerceIn(4, 6)
        val quick = if (w.time == CookingTime.EXPRESS) " rapide" else ""
        val seasonal = (0 until count).map { i ->
            val vegetable = rotated[i % rotated.size]
            val kind = slots[i * slots.size / count]
            val text = when (kind) {
                DishKind.VEGE -> "$vegetable végétarien"
                DishKind.POISSON -> "$vegetable poisson"
                DishKind.VIANDE -> "$vegetable ${meats[i % meats.size]}"
                null -> vegetable
            } + quick
            Spec(text, vegetable, kind, seasonal = true)
        }
        val useUp = w.useUp.take(2).map { Spec(it.trim() + quick, it.trim()) }
        val wished = w.wishList().mapNotNull(::wishQuery).take(2).map { Spec(it, it) }
        // Les restes à écouler et les envies d'abord : ce sont les demandes explicites.
        return (useUp + wished + seasonal).filter { it.text.isNotBlank() }.distinctBy { it.text }
    }

    fun queries(wishes: MenuWishes, history: MenuHistory, date: LocalDate = today(), round: Int = 0): List<String> =
        specs(wishes, history, date, round).map { it.text }

    private suspend fun local(): CatalogStore? = catalog?.takeIf { runCatching { it.count() }.getOrDefault(0) > 0 }

    /** Candidates tirées du catalogue local pour une recherche : aucune lecture de page. */
    private suspend fun fromCatalog(
        store: CatalogStore, query: CatalogQuery, wishes: MenuWishes, past: MenuHistory, date: LocalDate, index: CookbookIndex, excluded: Set<String>,
    ): List<Candidate> {
        val list = pantry()
        return store.search(query).asSequence()
            .filter { e ->
                RecipeSites.normalize(e.url) !in excluded && !index.isBanned(e.title) && !past.eatenRecently(e.title, e.url) && e.ingredients.size >= 3
            }
            .distinctBy { ShoppingListMerger.key(it.title) }
            .map { e ->
                val known = index.byUrl(e.url)
                Candidate.of(known ?: e.toRecipe(), wishes, past, date, list, fromCookbook = known != null, rating = e.rating)
            }
            .sortedByDescending { it.base }
            .toList()
    }

    private suspend fun findLocal(
        store: CatalogStore, w: MenuWishes, round: Int, exclude: Set<String>, draw: Int?, onProgress: (String) -> Unit,
    ): FindResult {
        val date = today()
        val cookbook = recipes.all()
        val past = MenuHistory(history.all(), cookbook, date)
        val index = CookbookIndex(cookbook)
        val excluded = exclude.map(RecipeSites::normalize).toSet()
        val specs = specs(w, past, date, round)
        onProgress("Recherche dans le catalogue de recettes…")
        val seed = draw ?: (date.get(ChronoField.ALIGNED_WEEK_OF_YEAR) * 31 + date.year + round * 7)
        val lists = specs.map { spec ->
            val query = CatalogQuery(
                words = CatalogQuery.words(spec.words), courses = CatalogQuery.MEALS, kind = spec.kind,
                // Les légumes de la semaine doivent donner des plats de saison ; une envie ou un reste à écouler, non.
                month = date.monthValue.takeIf { spec.seasonal }, maxMinutes = w.time.maxMinutes, limit = LOCAL_FETCH, seed = seed,
            )
            perSite(fromCatalog(store, query, w, past, date, index, excluded), LOCAL_PER_SITE).take(LOCAL_KEEP)
        }
        // Aucun site ne fournit plus du tiers des candidates.
        val fromCatalog = perSite(RecipeSearcher.interleave(lists).distinctBy { ShoppingListMerger.key(it.recipe.title) }, MAX_IMPORTS / 3).take(MAX_IMPORTS)
        val fromBook = cookbookCandidates(cookbook, w, past, date)
        return FindResult((fromCatalog + fromBook).sortedByDescending { it.base }, specs.map { it.text }, emptyList())
    }

    /** Lance les recherches, lit les fiches et renvoie les candidates notées. [exclude] : adresses déjà écartées. */
    suspend fun find(
        wishes: MenuWishes,
        round: Int = 0,
        exclude: Set<String> = emptySet(),
        /** Tirage : deux recherches avec des nombres différents ne sortent pas les mêmes recettes du catalogue. */
        seed: Int? = null,
        onProgress: (String) -> Unit = {},
    ): FindResult {
        val w = wishes.normalized()
        local()?.let { store -> findLocal(store, w, round, exclude, seed, onProgress).takeIf { it.candidates.size >= w.meals }?.let { return it } }
        val date = today()
        val cookbook = recipes.all()
        val past = MenuHistory(history.all(), cookbook, date)
        val queries = queries(w, past, date, round)

        onProgress("Recherche sur ${sites.size} sites de recettes…")
        val (lists, failed) = searchAll(queries) { done -> onProgress("Recherche sur ${sites.size} sites de recettes… ($done/${queries.size})") }
        val index = CookbookIndex(cookbook)
        val excluded = exclude.map(RecipeSites::normalize).toSet()
        val hits = RecipeSearcher.interleave(lists.map { l -> l.filter { acceptable(it, w, index, past, excluded) } })
            .distinctBy { RecipeSites.normalize(it.url) }
            .distinctBy { ShoppingListMerger.key(it.title) }
            .take((w.meals * 3).coerceIn(12, MAX_IMPORTS))

        val fromSites = import(hits, w, past, date, index) { done -> onProgress("Lecture des recettes… ($done/${hits.size})") }
        val fromBook = cookbookCandidates(cookbook, w, past, date)
        return FindResult((fromSites + fromBook).sortedByDescending { it.base }, queries, failed)
    }

    /**
     * Entrées ou desserts du menu : [count] recettes du type [courses] (catalogue local d'abord, sinon recherche sur les sites).
     * [exclude] : adresses déjà au menu ou écartées.
     */
    suspend fun forCourse(
        courses: Set<fr.mescourses.core.catalog.Course>, count: Int, wishes: MenuWishes,
        exclude: Set<String> = emptySet(), seed: Int? = null, fallbackWords: String = "",
    ): List<Candidate> {
        if (count <= 0) return emptyList()
        val date = today()
        val cookbook = recipes.all()
        val past = MenuHistory(history.all(), cookbook, date)
        val index = CookbookIndex(cookbook)
        val excluded = exclude.map(RecipeSites::normalize).toSet()
        local()?.let { store ->
            val query = CatalogQuery(courses = courses, limit = LOCAL_FETCH, seed = seed, month = date.monthValue)
            var found = fromCatalog(store, query, wishes, past, date, index, excluded)
            // Pas assez de recettes de saison : on complète avec le reste du catalogue plutôt que de rendre moins que demandé.
            if (found.size < count * 3) {
                val have = found.map { ShoppingListMerger.key(it.recipe.title) }.toSet()
                found = found + fromCatalog(store, query.copy(month = null), wishes, past, date, index, excluded)
                    .filter { ShoppingListMerger.key(it.recipe.title) !in have }
            }
            // Un tirage varié : on mélange les mieux notées au lieu de toujours prendre les mêmes.
            val shuffled = found.take(count * 6).shuffled(kotlin.random.Random(seed ?: 0))
            // Au plus 2 recettes par site si possible, mais on complète pour atteindre le nombre demandé.
            val varied = perSite(shuffled, 2).take(count)
            val picked = varied + (shuffled - varied.toSet()).take(count - varied.size)
            if (picked.isNotEmpty()) return picked
        }
        if (fallbackWords.isBlank()) return emptyList()
        val (lists, _) = searchAll(listOf(fallbackWords)) {}
        val hits = lists.flatten()
            .filter { RecipeSites.normalize(it.url) !in excluded && !index.isBanned(it.title) && !past.eatenRecently(it.title, it.url) }
            .distinctBy { RecipeSites.normalize(it.url) }
            .take(count * 3)
        return import(hits, wishes.copy(time = CookingTime.LONG), past, date, index) {}.take(count)
    }

    /** Recherche à la demande (outil du LLM, « autre recette ») : quelques candidates pour des mots donnés. */
    suspend fun search(
        text: String, wishes: MenuWishes, limit: Int = 6, exclude: Set<String> = emptySet(),
        /** Identifiants des sites voulus (« une recette de Jow ») ; vide = tous. */
        sites: Set<String> = emptySet(),
        seed: Int? = null,
        /** Types de recette voulus (plats par défaut ; entrées ou desserts pour « ajoute un dessert »). */
        courses: Set<fr.mescourses.core.catalog.Course> = CatalogQuery.MEALS,
    ): List<Candidate> {
        val date = today()
        val cookbook = recipes.all()
        val past = MenuHistory(history.all(), cookbook, date)
        val index = CookbookIndex(cookbook)
        val excluded = exclude.map(RecipeSites::normalize).toSet()
        local()?.let { store ->
            val words = CatalogQuery.words(text)
            val base = CatalogQuery(
                words = words, courses = courses, maxMinutes = wishes.time.maxMinutes, limit = LOCAL_FETCH, sites = sites,
                // Sans mots (« une recette de Jow ») : des plats de saison, tirés au sort parmi les bien notés.
                month = date.monthValue.takeIf { words.isEmpty() }, seed = seed,
            )
            // « raclette », « crêpes » : pas toujours rangés parmi les plats, on élargit si rien ne sort.
            val found = fromCatalog(store, base, wishes, past, date, index, excluded)
                .ifEmpty { fromCatalog(store, base.copy(courses = emptySet()), wishes, past, date, index, excluded) }
            if (found.isNotEmpty()) return found.take(limit)
            if (sites.isNotEmpty()) return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val (lists, _) = searchAll(listOf(text.trim())) {}
        val hits = lists.flatten().filter { acceptable(it, wishes, index, past, excluded) }
            .distinctBy { RecipeSites.normalize(it.url) }.take(limit)
        return import(hits, wishes, past, date, index) {}.sortedByDescending { it.base }
    }

    private suspend fun searchAll(queries: List<String>, onDone: (Int) -> Unit): Pair<List<List<RecipeHit>>, List<String>> = coroutineScope {
        val gate = Semaphore(3)
        var done = 0
        val results = queries.map { q ->
            async {
                gate.withPermit { runCatching { searcher.search(q, sites, PER_SITE) }.getOrNull() }.also { synchronized(gate) { onDone(++done) } }
            }
        }.awaitAll()
        val answered = results.filterNotNull()
        val failed = sites.filter { s -> answered.isEmpty() || answered.all { s in it.failures.keys } }.map { it.name }
        results.map { it?.hits ?: emptyList() } to failed
    }

    private fun acceptable(hit: RecipeHit, wishes: MenuWishes, index: CookbookIndex, past: MenuHistory, excluded: Set<String>): Boolean {
        val max = wishes.time.maxMinutes
        return RecipeSites.normalize(hit.url) !in excluded &&
            !index.isBanned(hit.title) &&
            !past.eatenRecently(hit.title, hit.url) &&
            (max == null || hit.totalMinutes == null || hit.totalMinutes <= max) &&
            RecipeFilters.badge(hit.title) != RecipeFilter.DESSERT
    }

    private suspend fun import(
        hits: List<RecipeHit>,
        wishes: MenuWishes,
        past: MenuHistory,
        date: LocalDate,
        index: CookbookIndex,
        onDone: (Int) -> Unit,
    ): List<Candidate> = coroutineScope {
        val gate = Semaphore(4)
        var done = 0
        val max = wishes.time.maxMinutes
        val list = pantry()
        hits.map { hit ->
            async {
                val recipe = gate.withPermit { runCatching { importer.import(hit.url) }.getOrNull() }
                synchronized(gate) { onDone(++done) }
                recipe?.takeIf { it.ingredients.size >= 3 && !index.isBanned(it.title) }
                    ?.let { r ->
                        // Une recette déjà dans le livre (même adresse) garde son identité : favori, notes, compteur.
                        val known = r.sourceUrl?.let { url -> index.byUrl(url) }
                        Candidate.of(known ?: r.copy(imageUrl = r.imageUrl ?: hit.imageUrl), wishes, past, date, list, fromCookbook = known != null, rating = hit.rating)
                    }
                    ?.takeIf { c -> max == null || (c.minutes ?: hit.totalMinutes ?: 0) <= max }
            }
        }.awaitAll().filterNotNull().distinctBy { ShoppingListMerger.key(it.recipe.title) }
    }

    /** Recettes du livre proposées à côté des découvertes : favoris d'abord, puis les plus anciennes. */
    private fun cookbookCandidates(cookbook: List<Recipe>, wishes: MenuWishes, past: MenuHistory, date: LocalDate): List<Candidate> {
        val wanted = kotlin.math.ceil(wishes.meals * wishes.novelty.cookbookShare * 2).toInt()
        if (wanted == 0) return emptyList()
        val max = wishes.time.maxMinutes
        val list = pantry()
        return cookbook.asSequence()
            .filter { !it.banned && it.ingredients.size >= 3 && !past.eatenRecently(it.title, it.sourceUrl) }
            .map { Candidate.of(it, wishes, past, date, list, fromCookbook = true) }
            .filter { c -> max == null || (c.minutes ?: 0) <= max }
            .sortedWith(compareByDescending<Candidate> { it.recipe.favorite }.thenByDescending { it.base }.thenBy { it.recipe.lastCookedAt ?: "" })
            .take(wanted)
            .toList()
    }
}

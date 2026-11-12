package fr.mescourses.core.catalog

import fr.mescourses.core.menu.DishKind
import fr.mescourses.core.recipes.FilterGroup
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeRating
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.delay

/** Une recherche dans le catalogue local. Tous les critères donnés doivent être remplis. */
data class CatalogQuery(
    /** Mots normalisés (voir [words]) : chacun doit commencer un mot du titre ou d'un ingrédient. */
    val words: List<String> = emptyList(),
    /** Types de plat acceptés ; vide = tous. */
    val courses: Set<Course> = emptySet(),
    val kind: DishKind? = null,
    /** Mois (1 à 12) où la recette doit être de saison. */
    val month: Int? = null,
    /** Durée maximale ; une recette sans durée connue est gardée. */
    val maxMinutes: Int? = null,
    val balanced: Boolean = false,
    /** Sites acceptés ; vide = tous. */
    val sites: Set<String> = emptySet(),
    val limit: Int = 60,
    /**
     * null : par note pondérée ([RecipeRating]) décroissante. Sinon : les bien notées d'abord, puis un mélange qui
     * dépend de ce nombre (le menu varie d'une semaine à l'autre).
     */
    val seed: Int? = null,
) {
    fun matches(e: CatalogEntry): Boolean {
        val tokens = e.searchText.split(' ')
        return words.all { w -> tokens.any { it.startsWith(w) } } &&
            (courses.isEmpty() || e.course in courses) &&
            (kind == null || e.kind == kind) &&
            (month == null || month in e.months) &&
            (maxMinutes == null || (e.minutes ?: 0) <= maxMinutes) &&
            (!balanced || e.balanced) &&
            (sites.isEmpty() || e.siteId in sites)
    }

    companion object {
        val MEALS: Set<Course> = Course.entries.filter { it.meal }.toSet()

        private val stopWords = setOf("de", "du", "des", "d", "la", "le", "les", "l", "au", "aux", "a", "et", "en", "un", "une", "avec", "sans", "recette")

        /** Mots utiles d'une saisie, sous la forme du texte de recherche des fiches (sans accents, au singulier). */
        fun words(text: String): List<String> =
            ShoppingListMerger.key(text).split(' ').filter { it.length > 1 && it !in stopWords }.distinct()

        /**
         * Recherche de Découvrir : la saisie devient des mots ; les filtres qui correspondent à une donnée de la fiche
         * (type, composition, saison, durée) deviennent des critères. Les autres filtres sont appliqués ensuite sur
         * les titres, comme pour une recherche en ligne.
         */
        fun of(text: String, filters: Set<RecipeFilter>, month: Int, sites: Set<String> = emptySet(), limit: Int = 300): CatalogQuery {
            val extra = filters.mapNotNull {
                when (it) {
                    RecipeFilter.BRUNCH -> "brunch"
                    RecipeFilter.FOUR -> "four"
                    RecipeFilter.MIJOTE -> "mijote"
                    else -> null
                }
            }
            val courses = filters.filter { it.group == FilterGroup.TYPE }.flatMap {
                when (it) {
                    RecipeFilter.ENTREE -> listOf(Course.ENTREE)
                    RecipeFilter.PLAT -> listOf(Course.PLAT)
                    RecipeFilter.DESSERT, RecipeFilter.GOUTER -> listOf(Course.DESSERT)
                    RecipeFilter.APERITIF -> listOf(Course.APERITIF)
                    RecipeFilter.SOUPE -> listOf(Course.SOUPE)
                    RecipeFilter.SALADE -> listOf(Course.SALADE)
                    RecipeFilter.ACCOMPAGNEMENT -> listOf(Course.ACCOMPAGNEMENT)
                    else -> emptyList()
                }
            }.toSet()
            val kinds = filters.mapNotNull {
                when (it) {
                    RecipeFilter.VEGETARIEN, RecipeFilter.VEGAN -> DishKind.VEGE
                    RecipeFilter.POISSON, RecipeFilter.FRUITS_DE_MER -> DishKind.POISSON
                    RecipeFilter.VIANDE, RecipeFilter.VOLAILLE -> DishKind.VIANDE
                    else -> null
                }
            }.toSet()
            return CatalogQuery(
                words = (words(text) + extra).distinct(),
                courses = courses,
                kind = kinds.singleOrNull(),
                month = month.takeIf { RecipeFilter.SAISON in filters },
                maxMinutes = filters.mapNotNull { it.maxMinutes }.minOrNull(),
                balanced = RecipeFilter.PLAT_UNIQUE in filters,
                sites = sites,
                limit = limit,
            )
        }
    }
}

/** Durée totale connue de la recette. */
val CatalogEntry.minutes: Int?
    get() = totalMinutes ?: listOfNotNull(prepMinutes, cookMinutes).takeIf { it.isNotEmpty() }?.sum()

/** La fiche telle que Découvrir affiche un résultat de recherche. */
fun CatalogEntry.toHit() = RecipeHit(
    title = title, url = url, siteId = siteId, siteName = RecipeSites.byId(siteId)?.name ?: siteId,
    imageUrl = imageUrl, rating = rating, ratingCount = ratingCount, totalMinutes = minutes,
)

/**
 * Classement « Pertinence » de Découvrir dans le catalogue local.
 *
 * Les sites n'ont ni la même taille ni les mêmes habitudes de notation (HelloFresh cumule des milliers d'avis par
 * recette, d'autres sites quelques dizaines) : un classement unique par nombre d'avis ne montrerait qu'un seul site.
 * On classe donc chaque site à part, par note pondérée ([RecipeRating]), puis on alterne les sites rang par rang :
 * la meilleure de chaque site, puis la 2ᵉ de chaque site, etc. Avec des mots cherchés, les recettes qui les ont dans
 * leur titre passent avant celles qui ne les ont que dans leurs ingrédients.
 */
object CatalogRanking {
    /** Recettes lues par site au minimum, pour qu'un site ne soit pas coupé trop tôt. */
    private const val MIN_PER_SITE = 40

    /**
     * [seed] null : les mieux notées de chaque site, toujours dans le même ordre. Sinon : un tirage parmi les bien
     * notées de chaque site, qui change avec ce nombre (Découvrir : une nouvelle liste à chaque rechargement).
     */
    suspend fun search(store: CatalogStore, query: CatalogQuery, seed: Int? = null): List<CatalogEntry> {
        val siteIds = query.sites.ifEmpty { store.countBySite().keys }
        if (siteIds.isEmpty()) return emptyList()
        val perSite = maxOf(MIN_PER_SITE, query.limit / siteIds.size)
        // Tirage avec des mots cherchés : on lit plus large, pour ne pas manquer les recettes qui les ont dans leur titre.
        val read = if (seed != null && query.words.isNotEmpty()) perSite * 3 else perSite
        val lists = siteIds.sorted().map { id -> store.search(query.copy(sites = setOf(id), limit = read, seed = seed)) }
        return rank(lists, query.words, seed?.let { kotlin.random.Random(it) }, perSite).take(query.limit)
    }

    /**
     * Alterne les listes (une par site) rang par rang ; à rang égal, la mieux notée d'abord.
     * Avec [random], l'ordre reçu de chaque site est gardé (c'est déjà un tirage) et, à rang égal, le hasard décide.
     */
    fun rank(
        lists: List<List<CatalogEntry>>,
        words: List<String> = emptyList(),
        random: kotlin.random.Random? = null,
        perSite: Int = Int.MAX_VALUE,
    ): List<CatalogEntry> {
        class Ranked(val entry: CatalogEntry, val inTitle: Boolean, val score: Double) { var rank = 0 }
        return lists.flatMap { list ->
            list.map {
                val score = random?.nextDouble() ?: RecipeRating.score(it.rating, it.ratingCount)
                Ranked(it, words.isNotEmpty() && inTitle(it.title, words), score)
            }.let { if (random == null) it.sortedWith(compareByDescending<Ranked> { r -> r.inTitle }.thenByDescending { r -> r.score }) else it.sortedByDescending { r -> r.inTitle } }
                .take(perSite)
                .onEachIndexed { i, r -> r.rank = i }
        }.sortedWith(compareByDescending<Ranked> { it.inTitle }.thenBy { it.rank }.thenByDescending { it.score })
            .map { it.entry }
    }

    /** Chaque mot cherché commence un mot du titre. */
    private fun inTitle(title: String, words: List<String>): Boolean {
        val tokens = ShoppingListMerger.key(title).split(' ')
        return words.all { w -> tokens.any { it.startsWith(w) } }
    }
}

/** Le catalogue local de recettes (base sur le téléphone). */
interface CatalogStore {
    suspend fun count(): Int
    suspend fun countBySite(): Map<String, Int>
    suspend fun search(query: CatalogQuery): List<CatalogEntry>
    suspend fun byUrl(url: String): CatalogEntry?
    /** Ajoute les fiches ; une fiche déjà connue (même adresse) est remplacée. */
    suspend fun upsert(entries: List<CatalogEntry>)
    /** Pages déjà lues d'un site (fiches du catalogue et pages sans recette) : adresse → date de modification vue. */
    suspend fun known(siteId: String): Map<String, String?>
    /** Retient une page lue qui n'a pas donné de recette, pour ne pas la relire. */
    suspend fun markEmpty(siteId: String, url: String, lastmod: String?)
    suspend fun meta(key: String): String?
    suspend fun setMeta(key: String, value: String)
}

/**
 * Mise à jour du catalogue par le téléphone : relit les sitemaps et ne lit que les fiches nouvelles ou modifiées,
 * les plus récentes d'abord, avec un plafond par site pour rester léger.
 */
class CatalogUpdater(
    private val store: CatalogStore,
    fetch: suspend (url: String) -> String,
    private val sources: List<CatalogSource> = CatalogSources.all,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val crawler = SiteCrawler(fetch)

    data class Report(val added: Int, val failedSites: List<String>)

    companion object {
        const val LAST_UPDATE = "derniere-mise-a-jour"
        const val DAY_MS = 24 * 3600 * 1000L
        private const val MAX_ERRORS = 5
    }

    /** Une mise à jour par jour suffit : les sites publient quelques recettes par jour. */
    suspend fun due(): Boolean = (store.meta(LAST_UPDATE)?.toLongOrNull() ?: 0L) + DAY_MS < now()

    /** Pages à lire pour un site, les plus récentes d'abord. */
    internal fun todo(pages: List<Sitemap.Page>, known: Map<String, String?>): List<Sitemap.Page> {
        // Le catalogue embarqué a déjà lu tout ce qui est plus ancien que sa fiche la plus récente.
        val newest = known.values.filterNotNull().maxOrNull()
        return pages.filter { p ->
            if (p.url !in known) p.lastmod == null || newest == null || p.lastmod > newest
            else p.lastmod != null && p.lastmod != known[p.url]
        }.sortedByDescending { it.lastmod.orEmpty() }
    }

    /** [only] : sites à mettre à jour (ceux dont la base est téléchargée) ; null = tous. */
    suspend fun update(maxPerSite: Int = 150, only: Set<String>? = null, onProgress: (String) -> Unit = {}): Report {
        var added = 0
        val failed = ArrayList<String>()
        for (source in sources.filter { only == null || it.siteId in only }) {
            val name = RecipeSites.byId(source.siteId)?.name ?: source.siteId
            onProgress("Nouveautés de $name…")
            val pages = try {
                crawler.pages(source, listingPages = 2)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed += name
                continue
            }
            val todo = todo(pages, store.known(source.siteId)).take(maxPerSite)
            val pause = (1000 / source.requestsPerSecond.coerceAtMost(1.0)).toLong()
            var errors = 0
            for ((i, page) in todo.withIndex()) {
                if (i % 10 == 0) onProgress("Nouveautés de $name… ($i/${todo.size})")
                delay(pause)
                try {
                    val entry = crawler.read(source, page)
                    if (entry != null) { store.upsert(listOf(entry)); added++ } else store.markEmpty(source.siteId, page.url, page.lastmod)
                    errors = 0
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Le site refuse ou ne répond plus : on n'insiste pas, la prochaine mise à jour reprendra.
                    if (++errors >= MAX_ERRORS) { failed += name; break }
                }
            }
        }
        store.setMeta(LAST_UPDATE, now().toString())
        return Report(added, failed)
    }
}

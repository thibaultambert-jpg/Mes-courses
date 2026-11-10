package fr.mescourses.core.catalog

import fr.mescourses.core.recipes.RecipeJsonLdExtractor

/**
 * Où trouver les recettes d'un site : ses sitemaps (relevés avec la sonde `tools/catalog/probe.py`).
 * Si un site change, c'est ici qu'il faut corriger.
 */
data class CatalogSource(
    /** Identifiant du site dans [fr.mescourses.core.recipes.RecipeSites]. */
    val siteId: String,
    /** Sitemaps de départ : un index (dont on suit les enfants) ou directement une liste d'adresses. */
    val sitemaps: List<String>,
    /** Parmi les sitemaps enfants d'un index, seuls ceux dont l'adresse correspond sont lus (null = tous). */
    val sitemapFilter: Regex? = null,
    /** Forme de l'adresse d'une fiche recette. */
    val recipeUrl: Regex,
)

object CatalogSources {
    val all = listOf(
        CatalogSource(
            "cuisineaz", listOf("https://www.cuisineaz.com/xml/sitemap.xml"),
            Regex("""sitemap-cuisineaz-recette-\d+"""), Regex("""cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"""),
        ),
        CatalogSource(
            "ptitchef", listOf("https://www.ptitchef.com/sitemap.xml"),
            Regex("""/recipe-fr-\d+\.xml"""), Regex("""ptitchef\.com/recettes/.+-fid-\d+$"""),
        ),
        CatalogSource(
            "marmiton", listOf("https://www.marmiton.org/wsitemap_recipes_index.xml"),
            Regex("""wsitemap_recipes_\d+"""), Regex("""marmiton\.org/recettes/recette_[^/]+\.aspx$"""),
        ),
        // Les recettes de Chef Simon sont rangées par auteur : /gourmets/<auteur>/recettes/<recette>.
        CatalogSource(
            "chefsimon", listOf("https://chefsimon.com/sitemap.xml"),
            Regex("""/sitemaps/sitemap\d+\.xml"""), Regex("""chefsimon\.com/gourmets/[^/]+/recettes/[^/]+$"""),
        ),
        CatalogSource(
            "hellofresh", listOf("https://www.hellofresh.fr/sitemap_recipe_pages.xml"),
            null, Regex("""hellofresh\.fr/recipes/[^/]+-[0-9a-f]{24}$"""),
        ),
        CatalogSource("jow", listOf("https://jow.fr/sitemap.xml"), null, Regex("""jow\.fr/recipes/[^/]+$""")),
        // Les sitemaps de recettes du Journal des Femmes sont fermés (410) : seules les recettes en vidéo sont listées.
        CatalogSource(
            "jdf", listOf("https://cuisine.journaldesfemmes.fr/sitemap/video-recipe/"),
            null, Regex("""journaldesfemmes\.fr/recette/\d+-[^/]+$"""),
        ),
    )

    fun byId(id: String): CatalogSource? = all.firstOrNull { it.siteId == id }
}

/**
 * Lit un site pour le catalogue : la liste de ses fiches (sitemaps), puis chaque fiche. Le même code sert à l'outil
 * de construction et à la mise à jour sur le téléphone ; [fetch] rend le texte d'une adresse (sitemaps .gz décompressés).
 */
class SiteCrawler(private val fetch: suspend (url: String) -> String) {

    /** Toutes les fiches recettes annoncées par le site, sans doublon, avec leur date de modification si elle est donnée. */
    suspend fun pages(source: CatalogSource, maxSitemaps: Int = 400): List<Sitemap.Page> {
        val queue = ArrayDeque(source.sitemaps)
        val seen = HashSet<String>()
        val pages = LinkedHashMap<String, Sitemap.Page>()
        while (queue.isNotEmpty() && seen.size < maxSitemaps) {
            val url = queue.removeFirst()
            if (!seen.add(url)) continue
            val sitemap = Sitemap.parse(fetch(url))
            queue += sitemap.children.filter { source.sitemapFilter?.containsMatchIn(it) ?: true }
            for (page in sitemap.pages) {
                val clean = page.url.substringBefore('#').substringBefore('?')
                if (source.recipeUrl.containsMatchIn(clean)) pages.putIfAbsent(clean, page.copy(url = clean))
            }
        }
        return pages.values.toList()
    }

    /** Lit une fiche ; null si la page n'a pas de recette lisible ou si elle n'a rien à faire au catalogue. */
    suspend fun read(source: CatalogSource, page: Sitemap.Page): CatalogEntry? {
        val details = RecipeJsonLdExtractor.extractDetails(fetch(page.url), page.url) ?: return null
        return CatalogEntry.of(details, source.siteId, page.url, page.lastmod).takeIf(CatalogPolicy::accepts)
    }
}

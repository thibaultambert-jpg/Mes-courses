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
    /** Rythme de lecture des fiches : certains sites refusent les lectures trop rapprochées. */
    val requestsPerSecond: Double = 1.5,
    /**
     * Pour un site sans sitemap exploitable : rubriques de recettes paginées. L'adresse donnée est la page 1, les
     * suivantes s'obtiennent avec « ?page=2 », « ?page=3 »… Une recette rangée dans plusieurs rubriques n'est lue qu'une fois.
     */
    val listings: List<String> = emptyList(),
    /**
     * Le sitemap donne la date de génération du fichier, la même pour toutes les adresses (Picnic) : elle ne dit rien d'une
     * modification. On l'ignore : seules les adresses nouvelles sont lues.
     */
    val ignoreLastmod: Boolean = false,
)

object CatalogSources {
    val all = listOf(
        CatalogSource(
            "cuisineaz", listOf("https://www.cuisineaz.com/xml/sitemap.xml"),
            Regex("""sitemap-cuisineaz-recette-\d+"""), Regex("""cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"""),
            // Cuisine AZ répond 403 quand on lit trop vite (constaté à 1,5 requête par seconde).
            requestsPerSecond = 0.8,
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
        // Sitemap de toutes les langues (nl, de, fr) : seules les fiches françaises (/fr/recettes/<id>/<nom>) sont gardées.
        // Les fiches n'ont pas de balise JSON-LD : la recette est lue dans le flux Next.js de la page (RecipeJsonLdExtractor).
        CatalogSource(
            "picnic", listOf("https://picnic.app/sitemap/recipes.xml"), null,
            Regex("""picnic\.app/fr/recettes/[0-9a-f]{24}/[^/]+$"""),
            requestsPerSecond = 1.0, ignoreLastmod = true,
        ),
        // 750g ne publie pas de sitemap de recettes : on suit ses rubriques de plats, page après page.
        CatalogSource(
            "750g", emptyList(), null, Regex("""750g\.com/[^/]+-r\d+\.htm$"""),
            requestsPerSecond = 1.0,
            listings = listOf(
                "https://www.750g.com/recettes_sante.htm",
                "https://www.750g.com/recettes-plats/restauration-rapide/",
                "https://www.750g.com/recettes-plats/desserts-sales/",
                "https://www.750g.com/recettes-plats/traditionnels/",
                "https://www.750g.com/recettes-plats/pates/",
                "https://www.750g.com/recettes-plats/pates/lasagnes/",
                "https://www.750g.com/recettes-plats/riz/",
                "https://www.750g.com/recettes-plats/riz/risottos/",
                "https://www.750g.com/recettes-plats/viandes/",
                "https://www.750g.com/recettes-plats/viandes/blanches/",
                "https://www.750g.com/recettes-plats/produits-mer/",
                "https://www.750g.com/recettes-plats/legumes/",
                "https://www.750g.com/recettes-plats/legumes/aubergine/",
                "https://www.750g.com/recettes-plats/cereales/",
                "https://www.750g.com/recettes-plats/fromages/",
            ),
        ),
        // Les sitemaps de recettes du Journal des Femmes sont fermés (410) : seules les recettes en vidéo sont listées.
        CatalogSource(
            "jdf", listOf("https://cuisine.journaldesfemmes.fr/sitemap/video-recipe/"),
            null, Regex("""journaldesfemmes\.fr/recette/\d+-[^/]+$"""),
        ),
        // Cuisine Actuelle : pas de sitemap de recettes non plus ; quatre rubriques couvrent le site.
        CatalogSource(
            "cuisineactuelle", emptyList(), null, Regex("""cuisineactuelle\.fr/recettes/[^/]+-\d+$"""),
            requestsPerSecond = 1.0,
            listings = listOf(
                "https://www.cuisineactuelle.fr/recettes-de-cuisine/plat",
                "https://www.cuisineactuelle.fr/recettes-de-cuisine/aperitif",
                "https://www.cuisineactuelle.fr/recettes-de-cuisine/entree",
                "https://www.cuisineactuelle.fr/recettes-de-cuisine/dessert",
            ),
        ),
        // Cuisine Addict : le sitemap ne liste pas les recettes ; quatre rubriques paginées (?page=N) les couvrent.
        CatalogSource(
            "cuisineaddict", emptyList(), null, Regex("""cuisineaddict\.com/recette-[^/]+\.htm$"""),
            requestsPerSecond = 1.0,
            listings = listOf(
                "https://www.cuisineaddict.com/recettes-entrees.htm",
                "https://www.cuisineaddict.com/recettes-plats.htm",
                "https://www.cuisineaddict.com/recettes-accompagnements.htm",
                "https://www.cuisineaddict.com/recettes-desserts.htm",
            ),
        ),
    )

    /** Sites lus par leurs rubriques : collectés à part des sites à sitemap (workflow « Catalogue — sites par rubriques »). */
    val byListings: List<CatalogSource> get() = all.filter { it.listings.isNotEmpty() }

    fun byId(id: String): CatalogSource? = all.firstOrNull { it.siteId == id }
}

/**
 * Lit un site pour le catalogue : la liste de ses fiches (sitemaps), puis chaque fiche. Le même code sert à l'outil
 * de construction et à la mise à jour sur le téléphone ; [fetch] rend le texte d'une adresse (sitemaps .gz décompressés).
 */
class SiteCrawler(private val fetch: suspend (url: String) -> String) {

    /** Toutes les fiches recettes annoncées par le site, sans doublon, avec leur date de modification si elle est donnée. */
    /** [listingPages] : pages lues au plus par rubrique (le téléphone ne regarde que les premières, où arrivent les nouveautés). */
    suspend fun pages(source: CatalogSource, maxSitemaps: Int = 400, listingPages: Int = 500): List<Sitemap.Page> {
        val queue = ArrayDeque(source.sitemaps)
        val seen = HashSet<String>()
        val pages = LinkedHashMap<String, Sitemap.Page>()
        for (listing in source.listings) {
            // Une rubrique s'arrête à la première page qui n'apporte aucune recette nouvelle pour elle (ou qui n'existe pas).
            val inListing = HashSet<String>()
            for (n in 1..listingPages) {
                val html = try {
                    fetch(if (n == 1) listing else "$listing?page=$n")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    break
                }
                val links = links(html, listing).filter { source.recipeUrl.containsMatchIn(it) }
                if (links.count { inListing.add(it) } == 0) break
                links.forEach { pages.putIfAbsent(it, Sitemap.Page(it, null)) }
            }
        }
        while (queue.isNotEmpty() && seen.size < maxSitemaps) {
            val url = queue.removeFirst()
            if (!seen.add(url)) continue
            val sitemap = Sitemap.parse(fetch(url))
            queue += sitemap.children.filter { source.sitemapFilter?.containsMatchIn(it) ?: true }
            for (page in sitemap.pages) {
                val clean = page.url.substringBefore('#').substringBefore('?')
                if (source.recipeUrl.containsMatchIn(clean)) pages.putIfAbsent(clean, page.copy(url = clean, lastmod = page.lastmod.takeUnless { source.ignoreLastmod }))
            }
        }
        return pages.values.toList()
    }

    private val href = Regex("""href\s*=\s*["']([^"'#?\s]+)""", RegexOption.IGNORE_CASE)

    /** Liens d'une page, en adresses complètes, sans paramètres ni ancre. */
    private fun links(html: String, base: String): List<String> {
        val origin = Regex("""^https?://[^/]+""").find(base)?.value.orEmpty()
        return href.findAll(html).map { it.groupValues[1] }.mapNotNull { link ->
            when {
                link.startsWith("http") -> link
                link.startsWith("//") -> "https:$link"
                link.startsWith("/") -> origin + link
                else -> null
            }
        }.distinct().toList()
    }

    /** Lit une fiche ; null si la page n'a pas de recette lisible ou si elle n'a rien à faire au catalogue. */
    suspend fun read(source: CatalogSource, page: Sitemap.Page): CatalogEntry? {
        val details = RecipeJsonLdExtractor.extractDetails(fetch(page.url), page.url) ?: return null
        return CatalogEntry.of(details, source.siteId, page.url, page.lastmod).takeIf(CatalogPolicy::accepts)
    }
}

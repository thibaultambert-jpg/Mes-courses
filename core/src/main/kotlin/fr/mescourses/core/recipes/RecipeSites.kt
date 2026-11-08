package fr.mescourses.core.recipes

import java.net.URI
import java.net.URLEncoder

/**
 * Sites de recettes connus de l'application.
 *
 * - [searchUrl] non nul : le site apparaît dans l'onglet « Découvrir » (recherche intégrée).
 * - Tous les sites listés : import d'une recette (données schema.org/Recipe) et navigation dans l'app.
 *
 * Les adresses de recherche et les formes d'URL de recettes sont les seuls éléments propres à chaque site :
 * si un site change, c'est ici qu'il faut corriger.
 */
data class RecipeSite(
    val id: String,
    val name: String,
    /** Domaine sans « www. » (ex. « marmiton.org »). */
    val domain: String,
    val homeUrl: String,
    /** Modèle d'URL de recherche, `{q}` est remplacé par la requête encodée ; null = pas de recherche intégrée. */
    val searchUrl: String? = null,
    /** Reconnaît l'adresse d'une page de recette (et pas d'une catégorie, d'un article…). */
    val recipeUrl: Regex,
    val note: String = "",
    /** Page des recettes populaires du moment ; à défaut, la page d'accueil (qui met en avant les tendances). */
    val popularUrl: String? = null,
    /** Page des recettes du site, ouverte par « Parcourir un site » (sinon la page d'accueil). */
    val browseUrl: String? = null,
) {
    val searchable: Boolean get() = searchUrl != null
    val popularPage: String get() = popularUrl ?: homeUrl
    val browsePage: String get() = browseUrl ?: homeUrl

    fun searchUrlFor(query: String): String? =
        searchUrl?.replace("{q}", URLEncoder.encode(query.trim(), Charsets.UTF_8).replace("+", "%20"))

    fun isRecipeUrl(url: String): Boolean = recipeUrl.containsMatchIn(url.substringBefore('#').substringBefore('?'))
}

object RecipeSites {

    val all: List<RecipeSite> = listOf(
        RecipeSite(
            id = "marmiton", name = "Marmiton", domain = "marmiton.org",
            homeUrl = "https://www.marmiton.org/",
            searchUrl = "https://www.marmiton.org/recettes/recherche.aspx?aqt={q}",
            recipeUrl = Regex("""marmiton\.org/recettes/recette_[^/]+\.aspx$"""),
            note = "Le plus grand catalogue, avec notes et avis.",
            browseUrl = "https://www.marmiton.org/recettes/index/categorie/plat-principal/",
        ),
        RecipeSite(
            id = "750g", name = "750g", domain = "750g.com",
            homeUrl = "https://www.750g.com/",
            searchUrl = "https://www.750g.com/recherche/?q={q}",
            recipeUrl = Regex("""750g\.com/[^/]+-r\d+\.htm$"""),
            note = "Recettes de chefs, durées bien renseignées.",
            popularUrl = "https://www.750g.com/recettes_populaires.htm",
            browseUrl = "https://www.750g.com/home_rubrique_-_recettes.htm",
        ),
        RecipeSite(
            id = "cuisineaz", name = "Cuisine AZ", domain = "cuisineaz.com",
            homeUrl = "https://www.cuisineaz.com/",
            searchUrl = "https://www.cuisineaz.com/recettes/recherche_terme.aspx?recherche={q}",
            browseUrl = "https://www.cuisineaz.com/categories/",
            recipeUrl = Regex("""cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"""),
            note = "Belles photos, ingrédients précis.",
        ),
        RecipeSite(
            id = "jdf", name = "Journal des Femmes", domain = "cuisine.journaldesfemmes.fr",
            homeUrl = "https://cuisine.journaldesfemmes.fr/",
            searchUrl = "https://cuisine.journaldesfemmes.fr/s/?f_libelle={q}",
            recipeUrl = Regex("""journaldesfemmes\.fr/recette/\d+-[^/]+$"""),
            browseUrl = "https://cuisine.journaldesfemmes.fr/toutes-les-recettes/",
            note = "Gros catalogue, beaucoup de recettes de saison.",
        ),
        RecipeSite(
            id = "cuisineactuelle", name = "Cuisine Actuelle", domain = "cuisineactuelle.fr",
            homeUrl = "https://www.cuisineactuelle.fr/",
            recipeUrl = Regex("""cuisineactuelle\.fr/recettes/[^/]+-\d+"""),
            browseUrl = "https://www.cuisineactuelle.fr/recettes-de-cuisine",
        ),
        RecipeSite(
            id = "ptitchef", name = "Ptitchef", domain = "ptitchef.com",
            homeUrl = "https://www.ptitchef.com/",
            recipeUrl = Regex("""ptitchef\.com/recettes/.+-fid-\d+"""),
            browseUrl = "https://www.ptitchef.com/recettes",
        ),
        RecipeSite(
            id = "ricardo", name = "Ricardo", domain = "ricardocuisine.com",
            homeUrl = "https://www.ricardocuisine.com/",
            recipeUrl = Regex("""ricardocuisine\.com/recettes/\d+-"""),
            browseUrl = "https://www.ricardocuisine.com/recettes/plats-principaux?currentPage=1&grouping-type=3&content-type=recipe&sort=alpha&searchValue=",
            note = "Québécois : unités parfois en tasses.",
        ),
        RecipeSite(
            id = "chefsimon", name = "Chef Simon", domain = "chefsimon.com",
            homeUrl = "https://chefsimon.com/",
            recipeUrl = Regex("""chefsimon\.com/recettes/[^/]+$"""),
            browseUrl = "https://chefsimon.com/recettes/tag/plat%20principal",
        ),
        RecipeSite(
            id = "elle", name = "Elle à table", domain = "elle.fr",
            homeUrl = "https://www.elle.fr/Elle-a-Table/Recettes-de-cuisine",
            // Fiches recettes seulement (« …/Gratin-dauphinois-2971574 »), pas les pages de catégories.
            recipeUrl = Regex("""elle\.fr/Elle-a-Table/Recettes-de-cuisine/(.+/)?[^/]+-\d+$"""),
            browseUrl = "https://www.elle.fr/Elle-a-Table/Recettes-de-cuisine",
        ),
        RecipeSite(
            id = "hellofresh", name = "HelloFresh", domain = "hellofresh.fr",
            homeUrl = "https://www.hellofresh.fr/recipes",
            recipeUrl = Regex("""hellofresh\.fr/recipes/[^/]+-[0-9a-f]{24}$"""),
            browseUrl = "https://www.hellofresh.fr/recipes#recettes-les-plus-populaires",
        ),
        RecipeSite(
            id = "picnic", name = "Picnic", domain = "picnic.app",
            homeUrl = "https://picnic.app/fr/recettes/",
            // Forme exacte inconnue : la détection de la recette dans la page (schema.org) prend le relais.
            recipeUrl = Regex("""picnic\.app/fr/recettes?/[^/]+/[^/]+$"""),
            browseUrl = "https://picnic.app/fr/recettes/",
        ),
        RecipeSite(
            id = "jow", name = "Jow", domain = "jow.fr",
            homeUrl = "https://jow.fr/",
            recipeUrl = Regex("""jow\.fr/recipes/[^/]+$"""),
            browseUrl = "https://jow.fr/blog/categories/idees-recettes",
        ),
    )

    val searchable: List<RecipeSite> get() = all.filter { it.searchable }

    fun byId(id: String): RecipeSite? = all.firstOrNull { it.id == id }

    /** Site connu correspondant à une adresse (sous-domaines compris), ou null. */
    fun forUrl(url: String): RecipeSite? {
        val host = hostOf(url) ?: return null
        return all.firstOrNull { host == it.domain || host.endsWith("." + it.domain) }
    }

    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.removePrefix("www.")

    /** Clé de comparaison d'une adresse de recette (sans protocole, « www. », paramètres ni « / » final). */
    fun normalize(url: String): String = url.trim()
        .substringBefore('#').substringBefore('?')
        .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        .removePrefix("www.")
        .trimEnd('/')
        .lowercase()

    /**
     * User-Agent identique à Chrome pour Android : sans « ; wv » ni « Version/4.0 ». Avec « Version/4.0 », certains
     * sites (Marmiton, 750g) croient avoir affaire au vieux navigateur d'Android 4 et servent un menu dégradé.
     */
    fun chromeUserAgent(webViewUa: String): String =
        webViewUa.replace("; wv", "").replace(Regex("""Version/\d+(\.\d+)*\s*"""), "").replace(Regex("\\s+"), " ").trim()

    /** Extrait la première adresse http(s) d'un texte partagé ou copié (« Regarde ça : https://… »). */
    fun findUrl(text: String?): String? =
        text?.let { Regex("""https?://[^\s"'<>]+""").find(it)?.value?.trimEnd('.', ',', ')', ';') }
}

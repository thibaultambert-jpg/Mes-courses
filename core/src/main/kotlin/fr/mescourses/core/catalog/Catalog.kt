package fr.mescourses.core.catalog

import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.ingredients.IngredientMapper
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.menu.DishKind
import fr.mescourses.core.menu.DishKinds
import fr.mescourses.core.menu.IngredientOverlap
import fr.mescourses.core.menu.PlateBalance
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeFilters
import fr.mescourses.core.recipes.RecipeJsonLdExtractor
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.season.Seasonality
import fr.mescourses.core.shopping.Pantry
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.serialization.Serializable
import java.time.Month

/** Place d'une recette dans un repas, d'après la catégorie donnée par le site ou, à défaut, son titre. */
enum class Course(val label: String) {
    PLAT("Plat"), SOUPE("Soupe"), SALADE("Salade"), ENTREE("Entrée"), ACCOMPAGNEMENT("Accompagnement"),
    DESSERT("Dessert"), APERITIF("Apéritif"), BOISSON("Boisson"), AUTRE("Autre");

    /** Peut faire un repas du menu. */
    val meal: Boolean get() = this == PLAT || this == SOUPE || this == SALADE

    companion object {
        private val words = listOf(
            DESSERT to listOf("dessert", "gateau", "patisserie", "tarte sucree", "biscuit", "glace", "creme dessert", "confiserie", "gouter", "viennoiserie", "confiture", "petit dejeuner", "crepe sucree", "mousse au chocolat"),
            BOISSON to listOf("boisson", "cocktail", "smoothie", "jus"),
            APERITIF to listOf("aperitif", "apero", "amuse bouche", "tapas", "dip", "toast"),
            SOUPE to listOf("soupe", "veloute", "potage", "bouillon"),
            SALADE to listOf("salade"),
            ACCOMPAGNEMENT to listOf("accompagnement", "sauce", "condiment", "pain"),
            ENTREE to listOf("entree"),
            PLAT to listOf("plat", "viande", "poisson", "volaille", "pate", "gratin", "quiche", "pizza", "curry", "risotto", "burger", "tajine", "wok", "poelee", "one pot", "plat unique", "plat complet"),
        )

        private fun find(text: String): Course? {
            val key = " " + ShoppingListMerger.key(text) + " "
            return words.firstOrNull { (_, list) -> list.any { " ${ShoppingListMerger.key(it)} " in key } }?.first
        }

        /** Catégorie du site d'abord (« Plat principal », « Entrées ») ; sinon le titre ; sinon l'équilibre de l'assiette. */
        fun of(categories: List<String>, title: String, ingredients: List<String>): Course {
            categories.firstNotNullOfOrNull(::find)?.let { return it }
            when (RecipeFilters.badge(title)) {
                RecipeFilter.DESSERT -> return DESSERT
                RecipeFilter.SOUPE -> return SOUPE
                RecipeFilter.SALADE -> return SALADE
                else -> Unit
            }
            find(title)?.let { return it }
            // Une protéine et des légumes ou un féculent : c'est un plat.
            val parts = PlateBalance.components(listOf(title) + ingredients)
            return if (PlateBalance.Component.PROTEINE in parts && parts.size >= 2) PLAT else AUTRE
        }
    }
}

/**
 * Une recette du catalogue local : de quoi la chercher, la trier et l'afficher sans réseau. Ni étapes ni image
 * (seulement le lien de l'image) : la fiche complète est lue sur le site quand la recette est choisie.
 */
@Serializable
data class CatalogEntry(
    /** Adresse de la fiche sur le site. */
    val url: String,
    val siteId: String,
    val title: String,
    val imageUrl: String? = null,
    /** Lignes d'ingrédients telles qu'écrites (« 200 g de farine »). */
    val ingredients: List<String> = emptyList(),
    val servings: Int = 4,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    /** Catégorie donnée par le site, telle quelle. */
    val category: String? = null,
    /** Date de modification annoncée par le sitemap (ISO), pour ne relire que ce qui a changé. */
    val lastmod: String? = null,
    // --- Calculé à la lecture de la fiche (voir [of]) ---
    val course: Course = Course.AUTRE,
    val kind: DishKind = DishKind.VEGE,
    /** Mois (1 à 12) où la recette est de saison : aucun fruit ou légume hors saison ce mois-là. */
    val months: List<Int> = emptyList(),
    /** Fruits et légumes à saison marquée de la recette (« poireau », « courge »). */
    val produce: List<String> = emptyList(),
    /** Assiette complète : protéine, légumes et féculent. */
    val balanced: Boolean = false,
    /** Clés des ingrédients à acheter (hors placard), comme dans le menu. */
    val keys: List<String> = emptyList(),
    /** Repères nutritionnels (léger, riche en fibres…) ; vide tant qu'ils ne sont pas calculés. */
    val nutritionTags: Set<NutritionTag> = emptySet(),
    /**
     * Vecteur sémantique de la fiche (256 dimensions, INT8) encodé en Base64, rempli par la CI ; null si absent.
     * Conservé tel quel par [recompute].
     */
    val embedding: String? = null,
) {
    val id: String get() = RecipeSites.normalize(url)

    /** Texte de recherche : titre et ingrédients, sans accents, au singulier. */
    val searchText: String get() = (listOf(ShoppingListMerger.key(title)) + keys).joinToString(" ")

    /** La recette telle que le reste de l'appli la manipule (sans étapes). */
    fun toRecipe() = Recipe(
        title = title, servings = servings, ingredients = IngredientParser.parseAll(ingredients),
        sourceUrl = url, imageUrl = imageUrl, prepMinutes = prepMinutes, cookMinutes = cookMinutes ?: totalMinutes?.minus(prepMinutes ?: 0),
        tags = listOfNotNull(category),
    )

    companion object {
        fun of(details: RecipeJsonLdExtractor.Details, siteId: String, url: String, lastmod: String? = null): CatalogEntry {
            val r = details.recipe
            val c = computed(r.title, r.ingredients.map { it.name }, r.ingredients.map { it.raw }, details.categories, details.totalMinutes ?: r.prepMinutes?.let { p -> r.cookMinutes?.let { p + it } })
            return CatalogEntry(
                url = url, siteId = siteId, title = r.title, imageUrl = r.imageUrl,
                ingredients = r.ingredients.map { it.raw },
                servings = r.servings, prepMinutes = r.prepMinutes, cookMinutes = r.cookMinutes, totalMinutes = details.totalMinutes,
                rating = details.rating?.let { Math.round(it * 10) / 10.0 }, ratingCount = details.ratingCount,
                category = details.categories.firstOrNull(), lastmod = lastmod,
                course = c.course, kind = c.kind, months = c.months, produce = c.produce, balanced = c.balanced,
                keys = c.keys, nutritionTags = c.nutritionTags,
            )
        }
    }
}

/**
 * Recalcule les champs calculés (type, viande/poisson/végé, saison, équilibre, clés, repères nutritionnels) depuis
 * le titre, les lignes d'ingrédients et la catégorie de la fiche ; garde tout le reste, y compris [CatalogEntry.embedding].
 * Sert après un changement des règles de calcul (mapping des ingrédients…), sans relire les sites.
 *
 * Donne le même résultat que [CatalogEntry.of] pour une fiche à une seule catégorie. Si le site en donnait plusieurs,
 * seule la première est gardée dans [CatalogEntry.category] : le type de plat ([Course]) peut alors différer quand
 * une catégorie suivante le décidait.
 */
fun CatalogEntry.recompute(): CatalogEntry {
    val c = computed(title, IngredientParser.parseAll(ingredients).map { it.name }, ingredients, listOfNotNull(category), totalMinutes ?: prepMinutes?.let { p -> cookMinutes?.let { p + it } })
    return copy(
        course = c.course, kind = c.kind, months = c.months, produce = c.produce, balanced = c.balanced,
        keys = c.keys, nutritionTags = c.nutritionTags,
    )
}

/**
 * Clés des ingrédients à acheter (hors placard) d'une recette : point d'édition unique du calcul des clés.
 * [raws] (lignes telles qu'écrites) sert au mapping canonique : une ligne ignorée est sautée, une ligne
 * reconnue donne sa clé canonique ; sinon la chaîne habituelle s'applique. Si [raws] est plus court que
 * [names], le nom sert de repli.
 */
fun keysOf(names: List<String>, raws: List<String>): List<String> =
    names.mapIndexedNotNull { i, name ->
        val line = IngredientMapper.mapLine(raws.getOrElse(i) { name })
        when {
            line?.status == IngredientMapper.Status.IGNORE -> null
            line?.key != null -> IngredientMapper.mapKey(line.key)
            else -> FoodName.clean(name).takeIf { it.isNotBlank() && !Pantry.isStaple(it) }?.let(IngredientOverlap::key)
        }
    }.filter { it.isNotEmpty() && IngredientMapper.status(it) != IngredientMapper.Status.IGNORE }.distinct()

/** Champs calculés d'une fiche du catalogue. */
private class Computed(
    val course: Course,
    val kind: DishKind,
    val months: List<Int>,
    val produce: List<String>,
    val balanced: Boolean,
    val keys: List<String>,
    val nutritionTags: Set<NutritionTag>,
)

private fun computed(title: String, names: List<String>, raws: List<String>, categories: List<String>, minutes: Int?): Computed {
    val texts = listOf(title) + names
    val keys = keysOf(names, raws)
    val course = Course.of(categories, title, names)
    // Saison : les fruits et légumes de la recette doivent tous se trouver ce mois-là.
    val months = Month.entries.filter { m -> Seasonality.report(texts, m).outOfSeason.isEmpty() }.map { it.value }
    val produce = Month.entries.flatMap { Seasonality.report(texts, it).seasonal }.distinct()
    return Computed(
        course = course,
        kind = DishKinds.of(title, names),
        months = months, produce = produce,
        balanced = PlateBalance.missing(texts).isEmpty(),
        keys = keys,
        nutritionTags = NutritionTags.of(title, keys, course, minutes),
    )
}

/**
 * Ce qui entre au catalogue : toute fiche lisible. Puisqu'il faut lire chaque fiche pour connaître sa note et son
 * type, on la garde ; la note et le type de plat servent ensuite à trier et filtrer les recherches, pas à écarter.
 */
object CatalogPolicy {
    /** « Bien noté » pour le classement des résultats et le choix des recettes du menu. */
    const val GOOD_RATING = 4.0
    const val MIN_RATING_COUNT = 3

    fun accepts(entry: CatalogEntry): Boolean = entry.title.isNotBlank() && entry.ingredients.size >= 2

    fun wellRated(entry: CatalogEntry): Boolean =
        (entry.rating ?: 0.0) >= GOOD_RATING && (entry.ratingCount ?: 0) >= MIN_RATING_COUNT
}

/** Un fichier de sitemap : soit un index d'autres sitemaps, soit une liste d'adresses avec leur date de modification. */
data class Sitemap(val children: List<String>, val pages: List<Page>) {
    data class Page(val url: String, val lastmod: String?)

    companion object {
        private val entry = Regex("""<(sitemap|url)\b[^>]*>(.*?)</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val loc = Regex("""<loc>\s*(?:<!\[CDATA\[)?\s*([^<\]\s]+)""", RegexOption.IGNORE_CASE)
        private val lastmod = Regex("""<lastmod>\s*([^<\s]+)""", RegexOption.IGNORE_CASE)

        fun parse(xml: String): Sitemap {
            val children = ArrayList<String>()
            val pages = ArrayList<Page>()
            for (m in entry.findAll(xml)) {
                val url = loc.find(m.groupValues[2])?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
                if (m.groupValues[1].equals("sitemap", ignoreCase = true)) children += url
                else pages += Page(url, lastmod.find(m.groupValues[2])?.groupValues?.get(1))
            }
            return Sitemap(children, pages)
        }
    }
}

package fr.mescourses.core.recipes

import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.Recipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

/**
 * Extrait une recette d'une page web grâce aux données structurées schema.org/Recipe (JSON-LD),
 * présentes sur Marmiton, 750g, Cuisine AZ, Journal des Femmes, Ricardo, etc.
 */
object RecipeJsonLdExtractor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extract(html: String, sourceUrl: String? = null): Recipe? =
        extractFromJsonLd(Jsoup.parse(html).select("script[type=application/ld+json]").map { it.data() }, sourceUrl)

    /** Même chose à partir du contenu des balises JSON-LD (lues par le navigateur intégré). */
    fun extractFromJsonLd(blocks: List<String>, sourceUrl: String? = null): Recipe? {
        for (block in blocks) {
            val element = runCatching { json.parseToJsonElement(block.trim()) }.getOrNull() ?: continue
            val recipeNode = findRecipe(element) ?: continue
            return toRecipe(recipeNode, sourceUrl)
        }
        return null
    }

    /** Une fiche lue pour le catalogue : la recette et ce que le site en dit (note, catégorie, durée totale). */
    data class Details(
        val recipe: Recipe,
        val rating: Double? = null,
        val ratingCount: Int? = null,
        val categories: List<String> = emptyList(),
        val totalMinutes: Int? = null,
    )

    fun extractDetails(html: String, sourceUrl: String? = null): Details? {
        for (block in Jsoup.parse(html).select("script[type=application/ld+json]").map { it.data() }) {
            val element = runCatching { json.parseToJsonElement(block.trim()) }.getOrNull() ?: continue
            val node = findRecipe(element) ?: continue
            val rating = node["aggregateRating"] as? JsonObject
            val recipe = toRecipe(node, sourceUrl)
            return Details(
                recipe = recipe,
                rating = rating?.string("ratingValue")?.replace(',', '.')?.toDoubleOrNull(),
                ratingCount = (rating?.string("ratingCount") ?: rating?.string("reviewCount"))?.toDoubleOrNull()?.toInt(),
                categories = strings(node["recipeCategory"]).map(::clean).filter { it.isNotBlank() },
                totalMinutes = node.string("totalTime")?.let(::parseIsoDurationMinutes)
                    ?: listOfNotNull(recipe.prepMinutes, recipe.cookMinutes).takeIf { it.isNotEmpty() }?.sum(),
            )
        }
        return null
    }

    private fun findRecipe(element: JsonElement): JsonObject? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> when {
            isRecipe(element) -> element
            element["@graph"] != null -> findRecipe(element["@graph"]!!)
            element["mainEntity"] != null -> findRecipe(element["mainEntity"]!!)
            else -> null
        }
        else -> null
    }

    private fun isRecipe(obj: JsonObject): Boolean = when (val type = obj["@type"]) {
        is JsonPrimitive -> type.contentOrNull.equals("Recipe", ignoreCase = true)
        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull.equals("Recipe", ignoreCase = true) }
        else -> false
    }

    private fun toRecipe(obj: JsonObject, sourceUrl: String?): Recipe {
        val title = obj.string("name")?.let(::clean) ?: "Recette sans titre"
        val ingredientLines = strings(obj["recipeIngredient"] ?: obj["ingredients"]).map(::clean)
        val steps = instructions(obj["recipeInstructions"]).map(::clean).filter { it.isNotBlank() }
        val prep = obj.string("prepTime")?.let(::parseIsoDurationMinutes)
        val cook = obj.string("cookTime")?.let(::parseIsoDurationMinutes)
        val keywords = obj.string("keywords")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val categories = strings(obj["recipeCategory"]) + strings(obj["recipeCuisine"])
        return Recipe(
            title = title,
            servings = parseServings(obj["recipeYield"]) ?: 4,
            ingredients = IngredientParser.parseAll(ingredientLines),
            steps = steps,
            sourceUrl = sourceUrl ?: obj.string("url"),
            imageUrl = image(obj["image"]),
            prepMinutes = prep,
            cookMinutes = cook,
            tags = (categories + keywords).distinct().take(8),
        )
    }

    private fun JsonObject.string(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun strings(element: JsonElement?): List<String> = when (element) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        is JsonArray -> element.flatMap { strings(it) }
        is JsonObject -> listOfNotNull((element["name"] as? JsonPrimitive)?.contentOrNull)
    }

    private fun instructions(element: JsonElement?): List<String> = when (element) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> element.contentOrNull.orEmpty()
            .split(Regex("\\n+|(?<=\\.)\\s+(?=\\d+[.)] )")).map { it.trim() }.filter { it.isNotEmpty() }
        is JsonArray -> element.flatMap { instructions(it) }
        is JsonObject -> when {
            element["itemListElement"] != null -> instructions(element["itemListElement"])
            else -> listOfNotNull(
                (element["text"] as? JsonPrimitive)?.contentOrNull ?: (element["name"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    private fun image(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.firstNotNullOfOrNull { image(it) }
        is JsonObject -> (element["url"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    fun parseServings(element: JsonElement?): Int? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        is JsonArray -> element.firstNotNullOfOrNull { parseServings(it) }
        else -> null
    }

    /** « PT1H30M » → 90. */
    fun parseIsoDurationMinutes(value: String): Int? {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val (d, h, min, _) = m.destructured
        val total = (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    private fun clean(text: String): String =
        Jsoup.parse(text).text().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
}

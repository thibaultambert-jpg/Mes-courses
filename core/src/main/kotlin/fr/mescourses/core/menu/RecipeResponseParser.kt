package fr.mescourses.core.menu

import fr.mescourses.core.agent.int
import fr.mescourses.core.agent.str
import fr.mescourses.core.agent.strList
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.Recipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Lit la recette générée par le LLM : JSON (même entouré de texte ou de ```), sinon texte à sections. */
object RecipeResponseParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Schéma JSON attendu par [parse] (ingrédients d'une recette, sans étapes). */
    val RECIPE_JSON_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "titre": { "type": "string" },
            "personnes": { "type": "integer" },
            "ingredients": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "aliment": { "type": "string" },
                  "quantite": { "type": "number" },
                  "unite": { "type": "string" }
                },
                "required": ["aliment", "quantite", "unite"]
              }
            }
          },
          "required": ["titre", "personnes", "ingredients"]
        }
    """.trimIndent()

    /** Schéma JSON attendu par [parseSteps] (étapes, temps et conseil). */
    val STEPS_JSON_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "preparation_min": { "type": "integer" },
            "cuisson_min": { "type": "integer" },
            "etapes": {
              "type": "array",
              "items": { "type": "string" }
            },
            "conseil": { "type": "string" }
          },
          "required": ["preparation_min", "cuisson_min", "etapes"]
        }
    """.trimIndent()

    fun parse(text: String, fallbackTitle: String, fallbackServings: Int): Recipe? =
        parseJson(text, fallbackTitle, fallbackServings) ?: parseText(text, fallbackTitle, fallbackServings)

    private fun parseJson(text: String, title: String, servings: Int): Recipe? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull() ?: return null
        val ingredients = ingredientLines(obj["ingredients"] ?: obj["ingrédients"])
        if (ingredients.isEmpty()) return null
        return Recipe(
            title = title,
            servings = obj.int("personnes") ?: obj.int("servings") ?: servings,
            ingredients = IngredientParser.parseAll(ingredients),
            steps = obj.strList("etapes").ifEmpty { obj.strList("étapes") }.ifEmpty { obj.strList("steps") },
            prepMinutes = obj.int("preparation_min"),
            cookMinutes = obj.int("cuisson_min"),
            notes = obj.str("conseil"),
        )
    }

    /** Ingrédients en chaînes (« 200 g de farine ») ou en objets ({"aliment","quantite","unite"}). */
    private fun ingredientLines(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.contentOrNull?.trim()
                is JsonObject -> {
                    val food = e.str("aliment") ?: e.str("nom") ?: e.str("name") ?: e.str("produit")
                    val qty = e.str("quantite") ?: e.str("quantité") ?: e.str("quantity")
                    val unit = (e.str("unite") ?: e.str("unité") ?: e.str("unit"))?.takeUnless { it in setOf("pièce", "pièces", "piece", "pieces", "unité", "u") }
                    food?.let { f ->
                        val q = qty?.takeUnless { it == "0" }
                        when {
                            q == null -> f
                            unit == null -> "$q $f"
                            else -> "$q $unit de $f"
                        }
                    }
                }
                else -> null
            }?.takeIf { it.isNotEmpty() }
        }
        is JsonPrimitive -> element.contentOrNull.orEmpty().split('\n', ';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** Étapes, temps et conseil écrits après coup pour une recette existante. */
    data class Steps(val steps: List<String>, val prepMinutes: Int?, val cookMinutes: Int?, val tip: String?)

    fun parseSteps(text: String): Steps? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull() else null
        if (obj != null) {
            val steps = obj.strList("etapes").ifEmpty { obj.strList("étapes") }.ifEmpty { obj.strList("steps") }
            if (steps.isNotEmpty()) return Steps(steps, obj.int("preparation_min"), obj.int("cuisson_min"), obj.str("conseil"))
        }
        // Repli : liste numérotée en texte.
        val steps = text.lines().map { it.trim() }.filter { it.matches(Regex("""^(\d+[.)]|[-•*])\s*.+""")) }
            .map { it.replace(Regex("""^(\d+[.)]|[-•*])\s*"""), "") }
        return if (steps.size >= 2) Steps(steps, null, null, null) else null
    }

    /** « Ingrédients : - … / Étapes : 1. … » */
    private fun parseText(text: String, title: String, servings: Int): Recipe? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<String>()
        var section = 0
        for (line in lines) {
            val lower = line.lowercase()
            when {
                lower.startsWith("ingr") -> section = 1
                lower.startsWith("étape") || lower.startsWith("etape") || lower.startsWith("préparation") || lower.startsWith("instructions") -> section = 2
                section == 1 && line.matches(Regex("""^[-•*]\s*.+""")) -> ingredients += line.trimStart('-', '•', '*', ' ')
                section == 2 && line.matches(Regex("""^(\d+[.)]|[-•*])\s*.+""")) -> steps += line.replace(Regex("""^(\d+[.)]|[-•*])\s*"""), "")
            }
        }
        if (ingredients.isEmpty()) return null
        return Recipe(title = title, servings = servings, ingredients = IngredientParser.parseAll(ingredients), steps = steps)
    }
}

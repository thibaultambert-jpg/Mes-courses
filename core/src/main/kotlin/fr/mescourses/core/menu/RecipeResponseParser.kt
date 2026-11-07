package fr.mescourses.core.menu

import fr.mescourses.core.agent.int
import fr.mescourses.core.agent.str
import fr.mescourses.core.agent.strList
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.Recipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Lit la recette générée par le LLM : JSON (même entouré de texte ou de ```), sinon texte à sections. */
object RecipeResponseParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String, fallbackTitle: String, fallbackServings: Int): Recipe? =
        parseJson(text, fallbackTitle, fallbackServings) ?: parseText(text, fallbackTitle, fallbackServings)

    private fun parseJson(text: String, title: String, servings: Int): Recipe? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull() ?: return null
        val ingredients = obj.strList("ingredients").ifEmpty { obj.strList("ingrédients") }
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

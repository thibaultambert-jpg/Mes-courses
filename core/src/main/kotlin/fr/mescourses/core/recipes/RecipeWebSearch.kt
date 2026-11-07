package fr.mescourses.core.recipes

import fr.mescourses.core.model.Recipe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WebResult(val title: String, val url: String, val snippet: String = "")

/** Recherche web (abstraite pour pouvoir changer de moteur). */
interface WebSearch {
    suspend fun search(query: String, limit: Int = 5): List<WebResult>
}

/**
 * Recherche via une instance SearXNG (auto-hébergée sur l'hôte maison, voir server/).
 * SearXNG agrège Google/Bing/DuckDuckGo sans clé d'API et répond en JSON.
 */
class SearxngWebSearch(
    private val http: HttpClient,
    private val baseUrl: () -> String,
) : WebSearch {

    @Serializable
    private data class SearxResponse(val results: List<SearxResult> = emptyList())

    @Serializable
    private data class SearxResult(val title: String = "", val url: String = "", val content: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, limit: Int): List<WebResult> {
        val base = baseUrl().trimEnd('/')
        require(base.isNotBlank()) { "Aucune instance SearXNG configurée (Réglages)." }
        val response = http.get("$base/search") {
            parameter("q", query)
            parameter("format", "json")
            parameter("language", "fr-FR")
            parameter("safesearch", 1)
            header("Accept", "application/json")
        }
        check(response.status.isSuccess()) { "SearXNG a répondu ${response.status}" }
        return json.decodeFromString<SearxResponse>(response.bodyAsText()).results
            .filter { it.url.startsWith("http") }
            .map { WebResult(it.title, it.url, it.content.take(200)) }
            .take(limit)
    }
}

/** Récupère une page et en extrait la recette. */
class RecipeImporter(private val http: HttpClient) {
    suspend fun import(url: String): Recipe {
        val response = http.get(url) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MesCourses/0.1")
            header("Accept-Language", "fr-FR,fr;q=0.9")
        }
        check(response.status.isSuccess()) { "La page a répondu ${response.status}" }
        return RecipeJsonLdExtractor.extract(response.bodyAsText(), url)
            ?: error("Aucune recette structurée (schema.org/Recipe) trouvée sur cette page.")
    }
}

/** Sites de recettes français privilégiés dans la recherche. */
object RecipeSites {
    val preferred = listOf(
        "marmiton.org", "750g.com", "cuisineaz.com", "journaldesfemmes.fr", "ricardocuisine.com",
        "cuisine-et-mets.com", "chefsimon.com", "jow.fr", "hellofresh.fr", "ptitchef.com",
    )

    fun recipeQuery(query: String) = "$query recette"
    fun rank(results: List<WebResult>): List<WebResult> =
        results.sortedBy { r -> if (preferred.any { r.url.contains(it) }) 0 else 1 }
}

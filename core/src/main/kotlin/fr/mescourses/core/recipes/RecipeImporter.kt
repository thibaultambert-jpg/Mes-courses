package fr.mescourses.core.recipes

import fr.mescourses.core.model.Recipe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

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

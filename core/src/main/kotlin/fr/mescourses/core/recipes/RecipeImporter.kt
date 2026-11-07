package fr.mescourses.core.recipes

import fr.mescourses.core.model.Recipe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Client HTTP classique : rapide, mais certains sites le bloquent (protection anti-robots). */
class HttpPageFetcher(private val http: HttpClient) : PageFetcher {
    override suspend fun fetch(url: String): String {
        val response = http.get(url) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            header("Accept", "text/html,application/xhtml+xml")
            header("Accept-Language", "fr-FR,fr;q=0.9")
        }
        check(response.status.isSuccess()) { "La page a répondu ${response.status.value}" }
        return response.bodyAsText()
    }
}

/**
 * Récupère une page et en extrait la recette.
 * Les [fetchers] sont essayés dans l'ordre (HTTP d'abord, puis navigateur invisible) jusqu'à trouver une recette.
 */
class RecipeImporter(private val fetchers: List<PageFetcher>) {
    constructor(http: HttpClient) : this(listOf(HttpPageFetcher(http)))

    suspend fun import(url: String): Recipe {
        var lastError: Exception? = null
        for (fetcher in fetchers) {
            try {
                RecipeJsonLdExtractor.extract(fetcher.fetch(url), url)?.let { return it }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        throw IllegalStateException(
            if (lastError != null && fetchers.size == 1) lastError.message
            else "Aucune recette trouvée sur cette page (le site ne publie pas de fiche recette lisible).",
            lastError,
        )
    }
}

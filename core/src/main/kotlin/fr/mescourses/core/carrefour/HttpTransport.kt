package fr.mescourses.core.carrefour

/** Réponse brute d'une requête faite depuis la WebView connectée. */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Transport HTTP. Dans l'app : un `fetch()` exécuté DANS une WebView connectée à carrefour.fr
 * (cookies et empreinte de vrai navigateur → passe Cloudflare). Dans les tests : un faux.
 */
fun interface HttpTransport {
    suspend fun request(method: String, path: String, headers: Map<String, String>, body: String?): HttpResponse
}

/** Erreurs remontées par le client, traduites en messages lisibles pour l'utilisateur et pour Gemma. */
sealed class CarrefourException(val code: String, message: String) : Exception(message) {
    class SessionExpired : CarrefourException("session_expiree", "L'utilisateur doit se reconnecter à Carrefour.")
    class ChallengeRequired : CarrefourException("verification_requise", "Carrefour demande une vérification anti-robot : ouvre le site dans l'app.")
    class RateLimited : CarrefourException("trop_de_requetes", "Carrefour limite les requêtes, réessaie dans un instant.")
    class NoDrive : CarrefourException("drive_non_choisi", "Aucun drive Carrefour choisi : choisis ton magasin.")
    class Forbidden(path: String) : CarrefourException("interdit", "Route interdite par l'application : $path")
    class Http(val status: Int) : CarrefourException("erreur_http", "Carrefour a répondu $status.")
    class Network(cause: String) : CarrefourException("reseau", "Connexion à Carrefour impossible : $cause")
}

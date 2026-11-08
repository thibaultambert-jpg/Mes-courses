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

/**
 * Erreurs remontées par les clients des enseignes (Carrefour, Leclerc, Auchan), traduites en messages lisibles
 * pour l'utilisateur et pour Gemma. `who` = nom de l'enseigne. Le nom historique est gardé pour limiter les changements.
 */
sealed class CarrefourException(val code: String, message: String) : Exception(message) {
    class SessionExpired(who: String = "Carrefour") : CarrefourException("session_expiree", "L'utilisateur doit se reconnecter à $who.")
    class ChallengeRequired(who: String = "Carrefour") : CarrefourException("verification_requise", "$who demande une vérification anti-robot : ouvre le site dans l'app.")
    class RateLimited(who: String = "Carrefour") : CarrefourException("trop_de_requetes", "$who limite les requêtes, réessaie dans un instant.")
    class NoDrive(who: String = "Carrefour") : CarrefourException("drive_non_choisi", "Aucun drive $who choisi : choisis ton magasin.")
    class Forbidden(path: String) : CarrefourException("interdit", "Route interdite par l'application : $path")
    class Http(val status: Int, who: String = "Carrefour") : CarrefourException("erreur_http", "$who a répondu $status.")
    class Network(cause: String, who: String = "Carrefour") : CarrefourException("reseau", "Connexion à $who impossible : $cause")
    class NotFound(what: String, who: String) : CarrefourException("introuvable", "$what introuvable chez $who.")
    class Unsupported(what: String, who: String) : CarrefourException("non_disponible", "$what n'est pas encore disponible pour $who.")
}

/** Nom générique des erreurs d'enseigne. */
typealias RetailerException = CarrefourException

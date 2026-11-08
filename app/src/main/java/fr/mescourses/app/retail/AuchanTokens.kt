package fr.mescourses.app.retail

import fr.mescourses.core.auchan.AuchanAuth
import fr.mescourses.core.auchan.AuchanRoutes
import java.util.concurrent.ConcurrentHashMap

/**
 * Jetons de l'API panier Auchan, relevés sur les requêtes que le site fait lui-même dans la WebView
 * (en mémoire seulement : jamais enregistrés, affichés ni journalisés).
 */
class AuchanTokens {
    private val headers = ConcurrentHashMap<String, String>()
    @Volatile private var consentId: String? = null

    fun seen(url: String, requestHeaders: Map<String, String>) {
        AuchanRoutes.CONSENT_REGEX.find(url)?.let { consentId = it.groupValues[1] }
        // Seuls les appels « checkout » portent les bonnes clés (les autres API du site ont les leurs).
        if (!url.startsWith(AuchanRoutes.API + "/checkout/")) return
        requestHeaders.forEach { (k, v) ->
            val key = k.lowercase()
            if (key in AuchanRoutes.API_HEADERS_TO_REPLAY && v.isNotBlank()) headers[key] = v
        }
    }

    fun current(): AuchanAuth? = if (headers.isEmpty() && consentId == null) null else AuchanAuth(HashMap(headers), consentId)

    /** Avant de recharger la page panier : on veut des jetons frais. */
    fun clear() {
        headers.clear()
    }
}

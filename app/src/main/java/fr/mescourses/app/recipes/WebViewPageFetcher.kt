package fr.mescourses.app.recipes

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import fr.mescourses.core.recipes.PageFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Charge une page dans une WebView invisible (vrai navigateur : JavaScript, cookies) puis en lit le HTML.
 * Sert quand un site de recettes refuse le client HTTP classique (protection anti-robots) ou construit
 * ses résultats en JavaScript. Plus lent que le HTTP : on ne l'utilise qu'en second recours.
 */
class WebViewPageFetcher(private val appContext: Context) : PageFetcher {

    private val permits = Semaphore(2)

    override suspend fun fetch(url: String): String = permits.withPermit {
        val finished = MutableStateFlow(false)
        val view = withContext(Dispatchers.Main) { createView(finished).also { it.loadUrl(url) } }
        try {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { finished.first { it } }
                ?: throw IOException("la page ne s'est pas chargée à temps")
            // Laisse le temps au JavaScript de la page (résultats, vérification anti-robots) de s'exécuter.
            delay(SETTLE_MS)
            withTimeout(CHALLENGE_TIMEOUT_MS) {
                var html = readHtml(view)
                while (isChallenge(html)) {
                    delay(1_000)
                    html = readHtml(view)
                }
                html
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { view.stopLoading(); view.destroy() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(finished: MutableStateFlow<Boolean>): WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.blockNetworkImage = true
        // Se présente comme Chrome (sans la mention « wv ») : moins de pages de vérification.
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                finished.value = true
            }
        }
    }

    private suspend fun readHtml(view: WebView): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                val html = runCatching { Json.decodeFromString(String.serializer(), raw) }.getOrDefault("")
                if (cont.isActive) cont.resume(html)
            }
        }
    }

    private fun isChallenge(html: String): Boolean =
        CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) } && html.length < 200_000

    private companion object {
        const val LOAD_TIMEOUT_MS = 25_000L
        const val SETTLE_MS = 1_200L
        const val CHALLENGE_TIMEOUT_MS = 15_000L
        val CHALLENGE_MARKERS = listOf("challenge-platform", "Just a moment", "captcha-delivery", "cf-chl-")
    }
}

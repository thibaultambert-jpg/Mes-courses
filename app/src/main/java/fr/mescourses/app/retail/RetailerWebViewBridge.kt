package fr.mescourses.app.retail

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.HttpResponse
import fr.mescourses.core.carrefour.HttpTransport
import fr.mescourses.core.auchan.AuchanRoutes
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.retail.Retailer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Une ligne du journal de debug (jamais de cookies ni de contenu). */
data class BridgeLog(val method: String, val route: String, val status: Int?, val millis: Long)

/**
 * Exécute les requêtes d'une enseigne par `fetch()` DANS une WebView connectée (1×1 px, attachée à l'activité).
 * Les cookies et l'empreinte de vrai navigateur passent les anti-robots (Cloudflare, DataDome), ce qu'un client
 * HTTP natif ne peut pas. Une instance par enseigne ; la page de départ dépend de la requête ([RetailerSite.pageFor]).
 */
class RetailerWebViewBridge(
    private val appContext: Context,
    val site: RetailerSite,
    private val uaWithoutWv: () -> Boolean,
    /** Requêtes faites par la page elle-même (URL + en-têtes), pour relever des jetons (Auchan). Jamais journalisé. */
    private val onRequestSeen: ((url: String, headers: Map<String, String>) -> Unit)? = null,
) : HttpTransport {

    private val who = site.retailer.label
    private val debug = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    @Volatile private var webView: WebView? = null
    /** Dernière page demandée à la WebView invisible. */
    @Volatile private var target: String? = null
    private val ready = MutableStateFlow(false)
    private val pageFinished = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<HttpResponse>>()
    /** Une requête à la fois : une requête peut exiger de changer de page, ce qui couperait les autres. */
    private val serial = Mutex()
    @Volatile private var lastRequestAt = 0L

    private val _log = MutableStateFlow<List<BridgeLog>>(emptyList())
    val log: StateFlow<List<BridgeLog>> = _log

    val isAttached: Boolean get() = webView != null

    /** Configuration commune aux WebViews des enseignes (invisible et visible). */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        // Inspection possible depuis chrome://inspect (usage personnel, pour capturer les routes).
        WebView.setWebContentsDebuggingEnabled(true)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // Les liens « nouvelle fenêtre » du site s'ouvrent dans la même vue au lieu d'être ignorés.
        view.settings.setSupportMultipleWindows(false)
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        // Leclerc et Auchan (DataDome) : présentés comme Chrome, dans les deux vues (cookies liés au navigateur).
        // Carrefour : réglage du debug (« se présenter comme Chrome »).
        if (site.retailer != Retailer.CARREFOUR) {
            view.settings.userAgentString = RecipeSites.chromeUserAgent(view.settings.userAgentString)
        } else if (uaWithoutWv()) {
            view.settings.userAgentString = view.settings.userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    /** Relève les requêtes de la page (Auchan : jetons de l'API panier). À appeler aussi depuis la WebView visible. */
    fun observe(request: WebResourceRequest?) {
        val cb = onRequestSeen ?: return
        val r = request ?: return
        runCatching { cb(r.url.toString(), r.requestHeaders.orEmpty()) }
    }

    /** Crée la WebView invisible ; appelée par l'interface (AndroidView) à chaque (re)création de l'activité. */
    fun createWebView(context: Context): WebView = WebView(context).also { view ->
        configure(view)
        view.addJavascriptInterface(JsBridge(), "MesCoursesBridge")
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                ready.value = false
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                ready.value = url != null && sameHost(url, target)
                url?.let { pageFinished.tryEmit(it) }
            }

            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                observe(request)
                return null
            }
        }
        webView = view
        val start = site.parking()
        target = start
        view.loadUrl(start)
    }

    fun detach(view: WebView) {
        if (webView === view) {
            webView = null
            ready.value = false
            pending.values.forEach { it.completeExceptionally(IOException("WebView détruite")) }
            pending.clear()
        }
        view.destroy()
    }

    private fun sameHost(a: String?, b: String?): Boolean {
        val ha = a?.let { Uri.parse(it).host } ?: return false
        val hb = b?.let { Uri.parse(it).host } ?: return false
        return ha.equals(hb, ignoreCase = true)
    }

    /** Charge `url` dans la WebView invisible. */
    private suspend fun load(url: String) = withContext(Dispatchers.Main) {
        target = url
        ready.value = false
        webView?.loadUrl(url)
    }

    /** Recharge la page de stationnement (après une connexion faite dans la WebView visible). */
    suspend fun reload() = load(site.parking())

    /** Recharge et attend la fin du chargement (au plus 20 s), pour que la vérification suivante voie les nouveaux cookies. */
    suspend fun reloadAndWait() {
        if (webView == null) return
        val page = site.parking()
        coroutineScope {
            val done = async { withTimeoutOrNull(20_000) { pageFinished.first { sameHost(it, page) } } }
            load(page)
            done.await()
        }
    }

    /**
     * Charge `url` et attend une page qui satisfait `until` (au plus `timeoutMs`) ; renvoie son adresse, ou null.
     * La page de stationnement est rechargée ensuite.
     */
    suspend fun visit(url: String, timeoutMs: Long = 25_000, until: (String) -> Boolean): String? = serial.withLock {
        if (webView == null) return@withLock null
        val landed = coroutineScope {
            val landing = async { withTimeoutOrNull(timeoutMs) { pageFinished.first(until) } }
            load(url)
            landing.await()
        }
        load(site.parking())
        landed
    }

    private suspend fun awaitReady(): WebView {
        val ok = withTimeoutOrNull(20_000) { ready.first { it } }
        return webView?.takeIf { ok != null }
            ?: throw CarrefourException.Network("page $who non chargée (application en arrière-plan ou hors connexion)", who)
    }

    override suspend fun request(method: String, path: String, headers: Map<String, String>, body: String?): HttpResponse =
        serial.withLock {
            val wait = lastRequestAt + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
            // Certaines requêtes doivent partir d'une autre page (autre drive Leclerc, recherche de magasins…).
            val page = if (path.startsWith("http")) site.pageFor(path) else site.parking()
            // La WebView peut être en cours de création (enseigne qu'on vient d'ajouter à la comparaison).
            val attached = withTimeoutOrNull(20_000) { while (webView == null) delay(100); true }
            if (attached != null && page != target) load(page)
            val view = awaitReady()
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<HttpResponse>()
            pending[id] = deferred
            val start = System.currentTimeMillis()
            val credentials = headers[AuchanRoutes.CREDENTIALS_HEADER] ?: "include"
            val sent = headers - AuchanRoutes.CREDENTIALS_HEADER
            val js = buildString {
                append("(function(){var o={method:").append(q(method))
                append(",headers:").append(Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), sent))
                append(",credentials:").append(q(credentials)).append("};")
                if (body != null) append("o.body=").append(q(body)).append(";")
                append("fetch(").append(q(path)).append(",o).then(function(r){var h={};r.headers.forEach(function(v,k){h[k]=v;});")
                append("return r.text().then(function(t){MesCoursesBridge.onResponse(").append(q(id)).append(",r.status,JSON.stringify(h),t);});})")
                append(".catch(function(e){MesCoursesBridge.onError(").append(q(id)).append(",String(e));});})();")
            }
            try {
                withContext(Dispatchers.Main) { view.evaluateJavascript(js, null) }
                val response = withTimeout(TIMEOUT_MS) { deferred.await() }
                record(method, path, response.status, System.currentTimeMillis() - start)
                response
            } catch (e: Exception) {
                record(method, path, null, System.currentTimeMillis() - start)
                if (e is CarrefourException) throw e
                throw CarrefourException.Network(e.message ?: e.toString(), who)
            } finally {
                pending.remove(id)
            }
        }

    private fun record(method: String, path: String, status: Int?, millis: Long) {
        if (!debug) return
        val route = path.substringBefore('?')
        Log.d(TAG, "${site.retailer} $method $route -> ${status ?: "échec"} (${millis} ms)")
        _log.value = (listOf(BridgeLog(method, route, status, millis)) + _log.value).take(50)
    }

    private fun q(s: String) = Json.encodeToString(String.serializer(), s)

    private inner class JsBridge {
        @JavascriptInterface
        fun onResponse(id: String, status: Int, headersJson: String, body: String) {
            val headers = runCatching {
                Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), headersJson)
            }.getOrDefault(emptyMap())
            pending[id]?.complete(HttpResponse(status, headers, body))
        }

        @JavascriptInterface
        fun onError(id: String, message: String) {
            pending[id]?.completeExceptionally(IOException(message))
        }
    }

    private companion object {
        const val TAG = "RetailerBridge"
        const val TIMEOUT_MS = 15_000L
        const val MIN_INTERVAL_MS = 400L
    }
}

package fr.mescourses.app.ui.retail

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.retail.RetailerSites
import fr.mescourses.app.retail.SessionState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.appContainer
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.core.retail.Retailer
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.Message
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

enum class WebMode { LOGIN, SITE }

/**
 * Le site d'une enseigne dans une WebView VISIBLE : connexion (captcha, code e-mail… saisis par l'utilisateur,
 * aucun mot de passe stocké), vérification anti-robot, choix du drive, finalisation de la commande.
 * Pour Carrefour, la connexion est détectée toute seule ; pour les autres, l'utilisateur touche « Terminé ».
 */
@Composable
fun RetailerWebScreen(retailer: Retailer, startUrl: String, mode: WebMode, onClose: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val retailerSession = c.session(retailer)
    val bridge = c.bridge(retailer)
    val isCarrefour = retailer == Retailer.CARREFOUR
    val session by retailerSession.state.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var visitedLogin by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }
    /** Fenêtre ouverte par le site (connexion dans une fenêtre à part), affichée par-dessus la page. */
    var popup by remember { mutableStateOf<WebView?>(null) }
    /** Diagnostic : hôte de la page affichée et dernière erreur de chargement (page principale). */
    var pageHost by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** Réponse attendue du test de session lancé DANS la WebView visible. */
    val probe = remember { AtomicReference<CompletableDeferred<Int>?>(null) }

    /** Route sécurisée (compte connecté) depuis la page visible (mêmes cookies que l'utilisateur) : renvoie le code HTTP, 0 si échec. */
    suspend fun probeVisible(): Int {
        val v = view ?: return 0
        val d = CompletableDeferred<Int>()
        probe.set(d)
        v.evaluateJavascript(
            "fetch('${CarrefourRoutes.SESSION_PROBE}',{credentials:'include',headers:{'accept':'application/json, text/plain, */*','x-requested-with':'XMLHttpRequest'}})" +
                ".then(function(r){MesCoursesLogin.onStatus(r.headers.get('cf-mitigated')?-1:r.status);})" +
                ".catch(function(){MesCoursesLogin.onStatus(0);});",
            null,
        )
        return withTimeoutOrNull(10_000) { d.await() } ?: 0
    }

    fun close(manual: Boolean) {
        if (closing) return
        closing = true
        scope.launch {
            if (isCarrefour && mode == WebMode.LOGIN && session != SessionState.CONNECTE && view?.url?.startsWith(CarrefourRoutes.ORIGIN) == true) {
                if (retailerSession.isLoggedInStatus(probeVisible())) retailerSession.markConnected()
            }
            retailerSession.onVisibleSiteClosed()
            if (mode == WebMode.LOGIN) {
                val who = retailer.label
                val msg = when {
                    retailerSession.state.value == SessionState.CONNECTE -> "Connecté à $who ✅"
                    manual -> "Pas encore connecté à $who : " + (retailerSession.lastProblem
                        ?: if (retailerSession.hasSsoCookie()) "session reçue mais pas encore active, réessaie dans un instant." else "connexion non terminée.")
                    else -> null
                }
                msg?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            onClose()
        }
    }

    BackHandler {
        val p = popup
        val v = view
        when {
            p != null && p.canGoBack() -> p.goBack()
            p != null -> popup = null
            v != null && v.canGoBack() -> v.goBack()
            else -> close(manual = true)
        }
    }

    // Connexion constatée : on ferme tout seul (inutile d'agir dans la page).
    LaunchedEffect(session) {
        if (isCarrefour && mode == WebMode.LOGIN && visitedLogin && session == SessionState.CONNECTE) close(manual = false)
    }

    ScreenScaffold(
        title = if (mode == WebMode.LOGIN) "Connexion ${retailer.label}" else retailer.site,
        onBack = { close(manual = true) },
        actions = { TextButton(onClick = { close(manual = true) }, enabled = !closing) { Text("Terminé") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (mode == WebMode.LOGIN) {
                InfoBanner(
                    "🔐",
                    "Connecte-toi avec ton e-mail et ton mot de passe ${retailer.label} (la connexion « via Google » ne marche pas ici). " +
                        "L'application ne voit ni ne garde ton mot de passe. " +
                        when (retailer) {
                            Retailer.CARREFOUR -> "L'écran se ferme tout seul une fois connecté."
                            Retailer.LECLERC -> "Vérifie que le site est bien sur ton drive, puis touche « Terminé »."
                            Retailer.AUCHAN -> "Touche « Me connecter » en haut de la page, choisis ton drive si besoin, puis « Terminé »."
                        },
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (loading || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Diagnostic discret : utile si une page reste blanche (hôte, erreur de chargement).
            Text(
                listOfNotNull(pageHost, loadError?.let { "⚠ $it" }).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )

            /** Clients communs à la page et aux fenêtres qu'elle ouvre. */
            fun setup(wv: WebView, isPopup: Boolean) {
                bridge.configure(wv)
                // Le site peut ouvrir de vraies fenêtres (connexion) : on les gère au lieu de perdre le lien avec la page.
                wv.settings.setSupportMultipleWindows(true)
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(v: WebView?, newProgress: Int) {
                        loading = newProgress < 100
                    }

                    override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                        val parent = v ?: return false
                        val msg = resultMsg ?: return false
                        val child = WebView(parent.context)
                        setup(child, isPopup = true)
                        popup = child
                        (msg.obj as? WebView.WebViewTransport)?.webView = child
                        msg.sendToTarget()
                        return true
                    }

                    override fun onCloseWindow(window: WebView?) {
                        if (window != null && window === popup) popup = null
                    }

                    // Le site demande la position (choix du magasin) : refus explicite, sinon la page reste bloquée.
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, false, false)
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.deny()
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    // Seules les pages web sont chargées ; une adresse « intent: », « mailto: »… viderait la page.
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != null && scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        pageHost = url?.let { android.net.Uri.parse(it).host }
                        loadError = null
                        if (isCarrefour && url?.startsWith(CarrefourRoutes.IAM_ORIGIN) == true) visitedLogin = true
                    }

                    override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) loadError = "${error?.description ?: "erreur"} (${request.url.host})"
                    }

                    override fun onReceivedHttpError(v: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                        if (request?.isForMainFrame == true) loadError = "HTTP ${errorResponse?.statusCode} (${request.url.host})"
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        // Carrefour : retour sur la boutique après la page de connexion, on teste la session dans cette page.
                        if (!isPopup && isCarrefour && mode == WebMode.LOGIN && visitedLogin && url?.startsWith(CarrefourRoutes.ORIGIN) == true) {
                            scope.launch {
                                CookieManager.getInstance().flush()
                                if (retailerSession.isLoggedInStatus(probeVisible())) retailerSession.markConnected()
                            }
                        }
                        // Leclerc : le drive ouvert sur le site devient le drive de l'application.
                        if (retailer == Retailer.LECLERC) RetailerSites.leclercStoreFromUrl(url)?.let { found ->
                            val current = c.settings.current.leclercStore()
                            if (current == null || current.id != found.id || current.host != found.host) scope.launch {
                                c.leclerc.forgetContext()
                                c.settings.update {
                                    it.copy(
                                        leclercStoreId = found.id, leclercNoPR = found.noPR, leclercHost = found.host,
                                        leclercStoreName = found.name.ifBlank { it.leclercStoreName.takeIf { _ -> current?.id == found.id }.orEmpty() },
                                    )
                                }
                            }
                        }
                    }

                    // Auchan : les jetons de l'API panier sont relevés aussi sur la page visible.
                    override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        bridge.observe(request)
                        return null
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            setup(wv, isPopup = false)
                            // Seule méthode exposée : le code HTTP du test de session.
                            wv.addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onStatus(status: Int) {
                                        probe.getAndSet(null)?.complete(status)
                                    }
                                },
                                "MesCoursesLogin",
                            )
                            wv.loadUrl(startUrl)
                            view = wv
                        }
                    },
                    onRelease = { it.destroy() },
                )
                popup?.let { child ->
                    key(child) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Fenêtre du site", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { popup = null }) { Text("Fermer") }
                            }
                            AndroidView(factory = { child }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
                        }
                    }
                }
            }
        }
    }
}

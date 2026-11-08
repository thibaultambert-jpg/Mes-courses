package fr.mescourses.app.ui.carrefour

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
import fr.mescourses.app.carrefour.SessionState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.appContainer
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.core.carrefour.CarrefourRoutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

enum class WebMode { LOGIN, SITE }

/**
 * carrefour.fr dans une WebView VISIBLE : connexion (captcha + code e-mail saisis par l'utilisateur,
 * aucun mot de passe stocké), vérification anti-robot, choix du drive, finalisation de la commande.
 */
@Composable
fun CarrefourWebScreen(startUrl: String, mode: WebMode, onClose: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by c.session.state.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var visitedLogin by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }
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
            if (mode == WebMode.LOGIN && session != SessionState.CONNECTE && view?.url?.startsWith(CarrefourRoutes.ORIGIN) == true) {
                if (c.session.isLoggedInStatus(probeVisible())) c.session.markConnected()
            }
            c.session.onVisibleSiteClosed()
            if (mode == WebMode.LOGIN) {
                val msg = when {
                    c.session.state.value == SessionState.CONNECTE -> "Connecté à Carrefour ✅"
                    manual -> "Pas encore connecté à Carrefour : " + (c.session.lastProblem
                        ?: if (c.session.hasSsoCookie()) "session reçue mais pas encore active, réessaie dans un instant." else "connexion non terminée.")
                    else -> null
                }
                msg?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            onClose()
        }
    }

    BackHandler {
        val v = view
        if (v != null && v.canGoBack()) v.goBack() else close(manual = true)
    }

    // Connexion constatée : on ferme tout seul (inutile d'agir dans la page).
    LaunchedEffect(session) {
        if (mode == WebMode.LOGIN && visitedLogin && session == SessionState.CONNECTE) close(manual = false)
    }

    ScreenScaffold(
        title = if (mode == WebMode.LOGIN) "Connexion Carrefour" else "carrefour.fr",
        onBack = { close(manual = true) },
        actions = { TextButton(onClick = { close(manual = true) }, enabled = !closing) { Text("Terminé") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (mode == WebMode.LOGIN) {
                InfoBanner(
                    "🔐",
                    "Connecte-toi avec ton e-mail et ton mot de passe Carrefour (la connexion « via Google » ne marche pas ici). " +
                        "L'application ne voit ni ne garde ton mot de passe. L'écran se ferme tout seul une fois connecté.",
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (loading || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { wv ->
                        c.bridge.configure(wv)
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
                        wv.webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                                loading = newProgress < 100
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
                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                if (url?.startsWith(CarrefourRoutes.IAM_ORIGIN) == true) visitedLogin = true
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                // Retour sur la boutique après la page de connexion : on teste la session dans cette page.
                                if (mode == WebMode.LOGIN && visitedLogin && url?.startsWith(CarrefourRoutes.ORIGIN) == true) {
                                    scope.launch {
                                        CookieManager.getInstance().flush()
                                        if (c.session.isLoggedInStatus(probeVisible())) c.session.markConnected()
                                    }
                                }
                            }
                        }
                        wv.loadUrl(startUrl)
                        view = wv
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

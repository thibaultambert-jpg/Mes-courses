package fr.mescourses.app.ui.carrefour

import android.graphics.Bitmap
import android.webkit.WebView
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.carrefour.SessionState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.appContainer
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.core.carrefour.CarrefourRoutes
import kotlinx.coroutines.launch

enum class WebMode { LOGIN, SITE }

/**
 * carrefour.fr dans une WebView VISIBLE : connexion (captcha + code e-mail saisis par l'utilisateur,
 * aucun mot de passe stocké), vérification anti-robot, choix du drive, finalisation de la commande.
 */
@Composable
fun CarrefourWebScreen(startUrl: String, mode: WebMode, onClose: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val session by c.session.state.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var visitedLogin by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }

    fun close() = scope.launch {
        c.session.onVisibleSiteClosed()
        onClose()
    }

    BackHandler {
        val v = view
        if (v != null && v.canGoBack()) v.goBack() else close()
    }

    LaunchedEffect(session) {
        if (mode == WebMode.LOGIN && visitedLogin && session == SessionState.CONNECTE) close()
    }

    ScreenScaffold(
        title = if (mode == WebMode.LOGIN) "Connexion Carrefour" else "carrefour.fr",
        onBack = { close() },
        actions = { TextButton(onClick = { close() }) { Text("Terminé") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (mode == WebMode.LOGIN) {
                InfoBanner(
                    "🔐",
                    "Connecte-toi avec ton e-mail et ton mot de passe Carrefour (la connexion « via Google » ne marche pas ici). " +
                        "L'application ne voit ni ne garde ton mot de passe.",
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { wv ->
                        c.bridge.configure(wv)
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                if (url?.startsWith(CarrefourRoutes.IAM_ORIGIN) == true) visitedLogin = true
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                loading = false
                                // Retour sur la boutique après la page de connexion : on vérifie la session.
                                if (mode == WebMode.LOGIN && visitedLogin && url?.startsWith(CarrefourRoutes.ORIGIN) == true) {
                                    scope.launch { c.session.onVisibleSiteClosed() }
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

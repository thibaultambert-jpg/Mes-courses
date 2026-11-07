package fr.mescourses.app.ui.carrefour

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.appContainer
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.core.carrefour.HttpResponse
import kotlinx.coroutines.launch

/** Masque e-mails, téléphones, noms et adresses avant export d'une réponse. */
fun anonymize(text: String): String = text
    .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "anonyme@exemple.invalid")
    .replace(Regex("""(?<!\d)(?:\+33|0)[1-9](?:[ .-]?\d{2}){4}(?!\d)"""), "0600000000")
    .replace(Regex(""""(firstName|lastName|firstname|lastname|name_on_card|customerName|email|phone|mobile|address1|address2|street|birthDate)"\s*:\s*"[^"]*""""), "\"$1\":\"***\"")

/** Écran de test des requêtes Carrefour (accès caché : 7 appuis sur la version, dans Réglages). */
@Composable
fun CarrefourDebugScreen(onBack: () -> Unit, onOpenSite: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by c.bridge.log.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var method by remember { mutableStateOf("GET") }
    var path by remember { mutableStateOf(CarrefourRoutes.CART) }
    var body by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<HttpResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var millis by remember { mutableStateOf(0L) }
    var driveInfo by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "Debug Carrefour", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Panier" to CarrefourRoutes.CART,
                    "Recherche lait" to CarrefourRoutes.search("lait"),
                    "Drive favori" to CarrefourRoutes.FAVORITE_STORE,
                ).forEach { (label, p) -> FilterChip(selected = path == p, onClick = { method = "GET"; path = p; body = "" }, label = { Text(label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("GET", "POST", "PATCH").forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) }) }
            }
            OutlinedTextField(path, { path = it }, label = { Text("Chemin") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (method != "GET") {
                OutlinedTextField(body, { body = it }, label = { Text("Corps JSON") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            }
            Button(onClick = {
                scope.launch {
                    error = null; result = null
                    val start = System.currentTimeMillis()
                    try {
                        result = c.carrefour.call(method, path, body.ifBlank { null })
                    } catch (e: Exception) {
                        error = "${e::class.simpleName} : ${e.message}"
                    }
                    millis = System.currentTimeMillis() - start
                }
            }) { Text("Envoyer") }

            // Identifiants du drive seulement (aucune donnée personnelle).
            OutlinedButton(onClick = {
                scope.launch {
                    driveInfo = "Recherche…"
                    val ctx = c.carrefour.discoverContext()
                    driveInfo = if (ctx == null) {
                        "Aucun identifiant de drive trouvé (panier vide et aucune offre dans la recherche)."
                    } else {
                        "basketServiceId : ${ctx.basketServiceId}\nsubBasketType : ${ctx.subBasketType}\n" +
                            "facilityServiceId : ${ctx.facilityServiceId ?: "—"}\nsource : ${c.carrefour.contextSource ?: "?"}"
                    }
                }
            }) { Text("Contexte drive") }
            driveInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { r ->
                SectionCard {
                    Text("Statut ${r.status} · $millis ms · ${r.body.length} caractères", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(r.body.take(3000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    OutlinedButton(onClick = {
                        val text = "$method ${path.substringBefore('?')}\nstatut ${r.status}\n\n" + anonymize(r.body)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                "Exporter la réponse (anonymisée)",
                            ),
                        )
                    }) { Text("Exporter (anonymisé)") }
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Se présenter comme Chrome", style = MaterialTheme.typography.bodyLarge)
                        Text("Retire « ; wv » du User-Agent si Cloudflare bloque la WebView (redémarrer l'app).", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.uaWithoutWv, onCheckedChange = { v -> scope.launch { c.settings.update { it.copy(uaWithoutWv = v) } } })
                }
                OutlinedButton(onClick = onOpenSite) { Text("Ouvrir la WebView visible") }
            }

            if (log.isNotEmpty()) {
                SectionCard {
                    Text("Journal (sans cookies ni contenu)", style = MaterialTheme.typography.titleSmall)
                    log.take(20).forEach { l ->
                        Text("${l.method} ${l.route} → ${l.status ?: "échec"} (${l.millis} ms)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

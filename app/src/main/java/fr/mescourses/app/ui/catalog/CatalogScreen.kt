package fr.mescourses.app.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.data.CatalogStatus
import fr.mescourses.app.data.CatalogSync
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.appContainer
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.core.catalog.CatalogSources
import fr.mescourses.core.recipes.RecipeSites
import java.util.Locale

/**
 * Choix des bases de recettes à télécharger (une par site). Premier écran au premier lancement ([firstRun]), puis
 * accessible depuis les Réglages et depuis les filtres de recherche.
 */
@Composable
fun CatalogScreen(firstRun: Boolean, onDone: () -> Unit) {
    val c = appContainer()
    val sync = c.catalogSync
    val st by sync.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { sync.checkRemote() }

    val sites = CatalogSources.all.map { it.siteId }.sortedBy { siteName(it).lowercase() }
    val missing = sites.filter { it !in st.installed }
    val outdated = sites.filter { st.outdated(it) }
    val busy = st.working != null

    ScreenScaffold(title = "Bases de recettes", onBack = if (firstRun) null else onDone) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (firstRun) "Choisis les sites dont tu veux avoir les recettes sur ton téléphone. Découvrir et le menu cherchent dedans sans réseau. " +
                    "Tu pourras en ajouter, les mettre à jour ou en retirer plus tard dans Réglages › Bases de recettes."
                else "Une base par site de recettes, enregistrée sur le téléphone. Seules les bases téléchargées sont utilisées par la recherche et le menu.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Chaque base pèse de quelques Mo à plusieurs dizaines de Mo : mieux vaut être en Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (missing.isNotEmpty()) {
                Button(onClick = { sync.install(missing) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (missing.size == sites.size) "Tout télécharger" else "Télécharger les ${missing.size} restantes")
                }
            }
            if (outdated.isNotEmpty()) {
                OutlinedButton(onClick = { sync.install(outdated) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (outdated.size == 1) "Mettre à jour la base disponible" else "Tout mettre à jour (${outdated.size} bases)")
                }
            }
            sites.forEach { id -> SiteRow(id, st, busy, onInstall = { sync.install(listOf(id)) }, onRemove = { sync.remove(id) }) }
            EmbedderRow(
                st = st,
                onInstall = { sync.installEmbedder() },
                onCancel = { sync.cancelEmbedder() },
                onRemove = { sync.removeEmbedder(c.embedder) },
            )

            st.working?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else st.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (firstRun) {
                Button(onClick = onDone, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (st.installed.isEmpty()) "Passer pour l'instant" else "Continuer")
                }
                if (st.installed.isEmpty()) Text(
                    "Sans base, la recherche se fait en ligne, site par site.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SiteRow(id: String, st: CatalogStatus, busy: Boolean, onInstall: () -> Unit, onRemove: () -> Unit) {
    val installed = id in st.installed
    val size = st.remote[id]?.bytes?.takeIf { it > 0 }?.let { mb(it) }
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard(contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(siteName(id), style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        installed -> "%,d recettes".format(Locale.FRANCE, st.bySite[id] ?: 0) + if (st.outdated(id)) " · mise à jour disponible" else " · à jour"
                        else -> "Non téléchargée" + (size?.let { " · $it" }.orEmpty())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                st.installing == id -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                !installed -> Button(onClick = onInstall, enabled = !busy) { Text("Télécharger") }
                st.outdated(id) -> OutlinedButton(onClick = onInstall, enabled = !busy) { Text("Mettre à jour") }
            }
            // Toute base installée peut être retirée (à jour ou non).
            if (installed && st.installing != id) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("Supprimer") }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Supprimer la base ${siteName(id)} ?") },
        text = {
            Text(
                "Ses %,d recettes seront retirées du téléphone : Découvrir et le menu ne les trouveront plus. ".format(Locale.FRANCE, st.bySite[id] ?: 0) +
                    "Tes favoris et ton historique ne changent pas. Tu pourras la télécharger de nouveau.",
            )
        },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Annuler") } },
    )
}

@Composable
private fun EmbedderRow(st: CatalogStatus, onInstall: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    val sizeBytes = st.embedderRemote?.bytes?.takeIf { it > 0 } ?: CatalogSync.DEFAULT_EMBEDDER_BYTES
    val sizeLabel = mb(sizeBytes)
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard(contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Recherche par le sens — $sizeLabel", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        st.embedderDownloading && st.embedderTotal > 0 ->
                            "Téléchargement… ${mb(st.embedderDownloaded)} / ${mb(st.embedderTotal)}"
                        st.embedderDownloading ->
                            "Téléchargement… ${mb(st.embedderDownloaded)}"
                        st.embedderInstalled ->
                            "Installée sur le téléphone · comprend les envies"
                        else ->
                            "Non téléchargée · comprend les envies (ex. « réconfortant », « léger »)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                st.embedderDownloading -> TextButton(onClick = onCancel) { Text("Annuler") }
                st.embedderInstalled -> TextButton(onClick = { confirmRemove = true }) { Text("Supprimer") }
                else -> Button(onClick = onInstall) { Text("Télécharger") }
            }
        }
        if (st.embedderDownloading) {
            if (st.embedderTotal > 0) {
                val progress = (st.embedderDownloaded.toFloat() / st.embedderTotal.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        st.embedderError?.let { err ->
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Supprimer la recherche par le sens ?") },
        text = {
            Text(
                "Le modèle ($sizeLabel) sera supprimé du téléphone. La recherche par mots-clés reste disponible et tu pourras le télécharger de nouveau.",
            )
        },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Annuler") } },
    )
}

private fun siteName(id: String) = RecipeSites.byId(id)?.name ?: id

private fun mb(bytes: Long) = if (bytes < 1_000_000) "<1 Mo" else "%.0f Mo".format(Locale.FRANCE, bytes / 1_000_000.0)

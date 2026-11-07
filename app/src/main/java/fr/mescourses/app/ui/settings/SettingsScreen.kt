@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.data.Settings
import fr.mescourses.app.llm.DownloadState
import fr.mescourses.app.llm.ModelCatalog
import fr.mescourses.app.llm.ModelInfo
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.core.mcp.Retailer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val settings = c.settings.state
    val download = c.models.state
    val installed = MutableStateFlow(c.models.installed())
    val testResult = MutableStateFlow<String?>(null)

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(transform) }

    fun download(model: ModelInfo) = c.models.download(model, hfToken = settings.value.hfToken) { path ->
        installed.value = c.models.installed()
        update { it.copy(modelPath = path) }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        runCatching { c.models.import(uri) }
            .onSuccess { path -> installed.value = c.models.installed(); update { it.copy(modelPath = path) } }
            .onFailure { testResult.value = "Import impossible : ${it.message}" }
    }

    fun delete(file: File) {
        c.models.delete(file)
        installed.value = c.models.installed()
        if (settings.value.modelPath == file.absolutePath) update { it.copy(modelPath = "") }
    }

    /** Teste la connexion MCP et affiche les outils détectés. */
    fun test(retailer: Retailer) = viewModelScope.launch {
        testResult.value = "Connexion à ${retailer.label}…"
        testResult.value = try {
            c.retailers.reset(retailer)
            val m = c.retailers.session(retailer).mapping
            "✅ ${retailer.label} connecté\nRecherche : ${m.searchTool}(${m.searchQueryArg})\n" +
                "Ajout : ${m.addTool ?: "—"}(${m.addProductArg ?: "?"}, ${m.addQuantityArg ?: "quantité par défaut"})\n" +
                "Panier : ${m.cartTool ?: "—"}" + if (m.fixedArgs.isNotEmpty()) "\nParamètres fixes : ${m.fixedArgs}" else ""
        } catch (e: Exception) {
            "❌ ${retailer.label} : ${e.message}"
        }
    }

    fun testSearxng() = viewModelScope.launch {
        testResult.value = try {
            val r = c.webSearch.search("quiche lorraine recette", 3)
            "✅ SearXNG : ${r.size} résultat(s)\n" + r.joinToString("\n") { "• ${it.title}" }
        } catch (e: Exception) {
            "❌ SearXNG : ${e.message}"
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    ScreenScaffold(title = "Réglages", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Modèle de langage local")
            Text(
                "Le modèle tourne entièrement sur le téléphone (LiteRT-LM, GPU Adreno). Espace libre : %.1f Go.".format(vm.c.models.freeSpaceGb()),
                style = MaterialTheme.typography.bodySmall,
            )
            installed.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.modelPath == file.absolutePath, onClick = { vm.update { it.copy(modelPath = file.absolutePath) } })
                    Text("${file.nameWithoutExtension} (%.1f Go)".format(file.length() / 1e9), Modifier.weight(1f))
                    TextButton(onClick = { vm.delete(file) }) { Text("Supprimer") }
                }
            }
            ModelCatalog.models.filter { m -> installed.none { it.name == m.fileName } }.forEach { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.label, style = MaterialTheme.typography.titleSmall)
                        Text("${m.description} (≈ ${m.approxSizeGb} Go)", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.download(m) }, enabled = download !is DownloadState.Running, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Télécharger")
                        }
                    }
                }
            }
            when (val d = download) {
                is DownloadState.Running -> Column {
                    Text("${d.label} : %.2f / %.2f Go".format(d.downloaded / 1e9, d.total / 1e9))
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.c.models.cancel() }) { Text("Annuler") }
                }
                is DownloadState.Failed -> Text(d.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Importer un fichier .litertlm") }
            Field("Jeton Hugging Face (si le modèle l'exige)", s.hfToken, secret = true) { v -> vm.update { it.copy(hfToken = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Utiliser le GPU (recommandé)", Modifier.weight(1f))
                Switch(checked = s.preferGpu, onCheckedChange = { v -> vm.update { it.copy(preferGpu = v) } })
            }

            HorizontalDivider()
            Section("Foyer et goûts")
            Field("Nombre de personnes par défaut", s.householdSize.toString(), number = true) { v ->
                v.toIntOrNull()?.let { n -> vm.update { it.copy(householdSize = n.coerceIn(1, 20)) } }
            }
            Field("Préférences (régime, allergies, budget…)", s.preferences, lines = 3) { v -> vm.update { it.copy(preferences = v) } }

            HorizontalDivider()
            Section("Hôte maison (MCP drive + recherche web)")
            Text(
                "Adresses exposées par le docker-compose de server/, joignables via Tailscale (ex. http://100.x.y.z:8811/carrefour/mcp).",
                style = MaterialTheme.typography.bodySmall,
            )
            Field("SearXNG (recherche de recettes)", s.searxngUrl) { v -> vm.update { it.copy(searxngUrl = v) } }
            TextButton(onClick = vm::testSearxng, enabled = s.searxngUrl.isNotBlank()) { Text("Tester SearXNG") }
            Field("Jeton d'accès MCP", s.mcpToken, secret = true) { v -> vm.update { it.copy(mcpToken = v) } }
            Retailer.entries.forEach { r ->
                Field("MCP ${r.label}", s.mcpUrl(r)) { v ->
                    vm.update {
                        when (r) {
                            Retailer.CARREFOUR -> it.copy(carrefourUrl = v)
                            Retailer.LECLERC -> it.copy(leclercUrl = v)
                            Retailer.AUCHAN -> it.copy(auchanUrl = v)
                        }
                    }
                }
                TextButton(onClick = { vm.test(r) }, enabled = s.mcpUrl(r).isNotBlank()) { Text("Tester ${r.label}") }
            }
            testResult?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

/** Champ texte qui n'enregistre qu'à la perte de focus / validation pour éviter d'écrire à chaque frappe. */
@Composable
private fun Field(label: String, value: String, secret: Boolean = false, number: Boolean = false, lines: Int = 1, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(600)
        if (text != value) onSave(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

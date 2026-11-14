@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fr.mescourses.app.ui.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import fr.mescourses.app.ui.components.PillStepper
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.SectionHeader
import fr.mescourses.app.ui.components.StatChip
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
import androidx.compose.material3.Slider
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import fr.mescourses.app.AppContainer
import fr.mescourses.app.retail.SessionState
import fr.mescourses.app.ui.retail.RetailerChips
import fr.mescourses.app.ui.retail.RetailerDot
import fr.mescourses.core.retail.Retailer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import fr.mescourses.app.data.Settings
import fr.mescourses.app.llm.DownloadState
import fr.mescourses.app.llm.ModelCatalog
import fr.mescourses.app.llm.ModelInfo
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val settings = c.settings.state
    val download = c.models.state
    val installed = MutableStateFlow(c.models.installed())

    /** Charge le modèle en mémoire (le chef devient actif). */
    fun activateChef() { viewModelScope.launch { c.model.refreshAvailability(); c.model.ensureLoaded() } }

    val testResult = MutableStateFlow<String?>(null)

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(transform) }

    init {
        // Téléchargement terminé (même lancé depuis une autre visite de l'écran) : liste à jour.
        viewModelScope.launch { c.models.state.collect { if (it is DownloadState.Done) installed.value = c.models.installed() } }
    }

    // Le choix du modèle est enregistré au niveau de l'application (c.selectModel), pas de l'écran :
    // quitter les Réglages pendant le téléchargement ne l'empêche plus.
    fun download(model: ModelInfo) = c.models.download(model, hfToken = settings.value.hfToken) { path -> c.selectModel(path) }

    fun import(uri: Uri) = c.appScope.launch {
        runCatching { c.models.import(uri) }
            .onSuccess { path -> installed.value = c.models.installed(); c.selectModel(path) }
            .onFailure { testResult.value = "Import impossible : ${it.message}" }
    }

    fun delete(file: File) {
        c.models.delete(file)
        installed.value = c.models.installed()
        if (settings.value.modelPath == file.absolutePath) update { it.copy(modelPath = "") }
    }

    fun session(r: Retailer) = c.session(r).state

    /** Vérifie la session des trois enseignes (leurs WebViews sont chargées le temps de l'écran). */
    fun startChecks() {
        c.checkingRetailers.value = Retailer.entries.toSet()
        Retailer.entries.forEach { r -> viewModelScope.launch { c.session(r).check() } }
    }

    fun stopChecks() {
        c.checkingRetailers.value = emptySet()
    }

    override fun onCleared() = stopChecks()
    fun problem(r: Retailer): String? = c.session(r).lastProblem

    fun logout() = c.logoutAll()
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onLogin: (Retailer) -> Unit, onChooseStore: (Retailer) -> Unit, onDebug: () -> Unit, onOpenCatalog: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    // État de connexion juste dès l'ouverture, sans attendre une comparaison des prix.
    DisposableEffect(Unit) {
        vm.startChecks()
        onDispose { vm.stopChecks() }
    }
    val s by vm.settings.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    var versionTaps by remember { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    ScreenScaffold(title = "Réglages", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                SectionHeader("Assistant local", emoji = "🤖")
                Text(
                    "Il tourne entièrement sur ton téléphone (LiteRT-LM, GPU Adreno). Espace libre : %.1f Go.".format(vm.c.models.freeSpaceGb()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val modelStatus by vm.c.model.status.collectAsStateWithLifecycle()
                if (installed.isNotEmpty()) when (val m = modelStatus) {
                    is ModelStatus.Ready -> StatChip("🤖 Chef actif · ${m.modelName} · ${m.backend}")
                    ModelStatus.Loading -> Text("Le chef s'active…", style = MaterialTheme.typography.bodyMedium)
                    is ModelStatus.Failed -> {
                        Text(m.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.activateChef() }) { Text("Réessayer d'activer le chef") }
                    }
                    else -> Button(onClick = { vm.activateChef() }) { Text("Activer le chef") }
                }
                installed.forEach { file ->
                    val active = s.modelPath == file.absolutePath
                    Card(
                        onClick = { vm.update { it.copy(modelPath = file.absolutePath) } },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = active, onClick = { vm.update { it.copy(modelPath = file.absolutePath) } })
                            Column(Modifier.weight(1f)) {
                                Text(file.nameWithoutExtension, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "%.1f Go".format(file.length() / 1e9) + if (active) " · utilisé" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { vm.delete(file) }) { Text("Supprimer") }
                        }
                    }
                }
                ModelCatalog.models.filter { m -> installed.none { it.name == m.fileName } }.forEach { m ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatChip("≈ ${m.approxSizeGb} Go")
                            }
                            Text(m.description, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { vm.download(m) }, enabled = download !is DownloadState.Running) { Text("Télécharger") }
                        }
                    }
                }
                when (val d = download) {
                    is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${d.label} : %.2f / %.2f Go".format(d.downloaded / 1e9, d.total / 1e9), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { d.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                        )
                        TextButton(onClick = { vm.c.models.cancel() }) { Text("Annuler") }
                    }
                    is DownloadState.Failed -> Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Importer un fichier .litertlm") }
                SwitchRow("Utiliser le GPU", "Recommandé : bien plus rapide", s.preferGpu) { v -> vm.update { it.copy(preferGpu = v) } }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mémoire de conversation (tokens)", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Auto", 4096 to "4 096", 6144 to "6 144", 8192 to "8 192").forEach { (tokens, label) ->
                            InputChip(
                                selected = s.contextTokens == tokens,
                                onClick = { vm.update { it.copy(contextTokens = tokens) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                Field("Jeton Hugging Face (si le modèle l'exige)", s.hfToken, secret = true) { v -> vm.update { it.copy(hfToken = v) } }
            }

            SectionCard {
                SectionHeader("Mon foyer", emoji = "👨‍👩‍👧")
                PillStepper("🧑", "Adultes par repas", s.householdSize, { n -> vm.update { it.copy(householdSize = n.coerceIn(0, 20)) } })
                PillStepper("🧒", "Enfants par repas", s.children, { n -> vm.update { it.copy(children = n.coerceIn(0, 20)) } })
                PillStepper("🥗", "Entrées par menu", s.starters, { n -> vm.update { it.copy(starters = n.coerceIn(0, 21)) } }, min = 0, max = 21)
                PillStepper("🍽", "Plats par menu", s.meals, { n -> vm.update { it.copy(meals = n.coerceIn(1, 21)) } }, min = 1, max = 21)
                PillStepper("🍰", "Desserts par menu", s.desserts, { n -> vm.update { it.copy(desserts = n.coerceIn(0, 21)) } }, min = 0, max = 21)
                Text("Portion d'un enfant : ${(s.childFactor * 100).toInt()} % d'une portion adulte", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.childFactor.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(childFactor = (Math.round(v * 20) / 20.0)) } },
                    valueRange = 0.25f..1f,
                    steps = 14,
                )
                Field("Régime, allergies, goûts, budget…", s.preferences, lines = 3) { v -> vm.update { it.copy(preferences = v) } }
            }

            SectionCard {
                SectionHeader("Toujours dans mon placard", emoji = "🧂")
                Text(
                    "Ces produits ne sont jamais ajoutés à la liste de courses depuis une recette. Touche-en un pour le retirer " +
                        "(tu l'achèteras), ou ajoute ce que tu as toujours chez toi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val pantry = s.pantryList()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pantry.forEach { item ->
                        InputChip(
                            selected = false,
                            onClick = { vm.update { it.withPantry(it.pantryList() - item) } },
                            label = { Text(item) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Retirer $item", Modifier.size(16.dp)) },
                        )
                    }
                }
                var newPantry by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPantry,
                        onValueChange = { newPantry = it },
                        label = { Text("Ajouter (ex. riz, café)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val v = newPantry.trim().replaceFirstChar { it.uppercase() }
                            if (v.isNotEmpty()) vm.update { st -> st.withPantry((st.pantryList() + v).distinct()) }
                            newPantry = ""
                        },
                        enabled = newPantry.isNotBlank(),
                    ) { Text("Ajouter") }
                }
                if (s.pantry.isNotEmpty()) TextButton(onClick = { vm.update { it.copy(pantry = "") } }) { Text("Revenir à la liste par défaut") }
            }

            SectionCard {
                SectionHeader("Mes drives", emoji = "🛒")
                Text("Enseigne préférée", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Utilisée par défaut pour le panier et l'assistant. Tu peux en changer pour un panier, ou comparer les prix, depuis l'écran Panier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RetailerChips(selected = s.preferredRetailer, preferred = null) { r -> vm.update { it.copy(retailer = r.name) } }
                Retailer.entries.forEach { r ->
                    val session by vm.session(r).collectAsState()
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RetailerDot(r, 32.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(r.label, style = MaterialTheme.typography.bodyLarge)
                            Text(s.storeName(r).ifBlank { r.noStoreText() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatChip(session.label, emphasized = session != SessionState.CONNECTE)
                    }
                    // Raison du dernier refus (ex. « page session expirée du drive 053701 »), pour comprendre sans debug.
                    if (session != SessionState.CONNECTE) vm.problem(r)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // « Drive à choisir » : on est connecté, il manque seulement le drive.
                        if (session != SessionState.CONNECTE && session != SessionState.DRIVE_REQUIS) Button(onClick = { onLogin(r) }) { Text("Se connecter") }
                        TextButton(onClick = { onChooseStore(r) }) { Text(r.chooseStoreText(s.storeName(r).isNotBlank())) }
                    }
                }
                HorizontalDivider()
                SwitchRow("Privilégier le bio", "Propose la version bio quand le drive en a une, sinon le produit classique", s.preferOrganic) { v ->
                    vm.update { it.copy(preferOrganic = v) }
                }
                OutlinedButton(onClick = vm::logout) { Text("Se déconnecter de toutes les enseignes") }
                Text(
                    "Intégrations non officielles, pour un usage personnel. La commande, le créneau et le paiement se font toujours sur le site de l'enseigne.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val catalog by vm.c.catalogSync.status.collectAsStateWithLifecycle()
            SectionCard {
                SectionHeader("Bases de recettes", emoji = "📚")
                Text(
                    if (catalog.count == 0) "Aucune base téléchargée : Découvrir et le menu cherchent sur les sites."
                    else "%,d recettes sur le téléphone (%d base%s). Découvrir et le menu y cherchent sans réseau.".format(
                        java.util.Locale.FRANCE, catalog.count, catalog.installed.size, if (catalog.installed.size > 1) "s" else "",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (catalog.bySite.isNotEmpty()) Text(
                    catalog.bySite.entries.sortedByDescending { it.value }
                        .joinToString(" · ") { (id, n) -> "${fr.mescourses.core.recipes.RecipeSites.byId(id)?.name ?: id} : %,d".format(java.util.Locale.FRANCE, n) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    catalog.working
                        ?: listOfNotNull(
                            catalog.lastUpdate?.let { "Nouveautés cherchées le " + java.text.SimpleDateFormat("d MMMM à HH:mm", java.util.Locale.FRANCE).format(java.util.Date(it)) }
                                ?: "Nouveautés jamais cherchées",
                            catalog.lastResult,
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (catalog.working != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = onOpenCatalog) { Text("Choisir les bases à télécharger") }
                OutlinedButton(onClick = { vm.c.catalogSync.updateNow() }, enabled = catalog.working == null && catalog.installed.isNotEmpty()) { Text("Chercher les nouveautés maintenant") }
                Text(
                    "Les nouveautés des bases téléchargées sont cherchées toutes seules, une fois par jour à l'ouverture de l'appli (hors données mobiles).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val update by vm.c.appUpdater.status.collectAsStateWithLifecycle()
            SectionCard {
                SectionHeader("Mise à jour de l'appli", emoji = "⬆️")
                Text(
                    if (update.updateAvailable) "Nouvelle version disponible (build ${update.available}, installée : ${update.installed})."
                    else "Version installée : build ${update.installed}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                (update.working ?: update.message)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (update.working != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (update.updateAvailable) Button(onClick = { vm.c.appUpdater.install() }, enabled = update.working == null) { Text("Mettre à jour") }
                    OutlinedButton(onClick = { vm.c.appUpdater.checkNow() }, enabled = update.working == null) { Text("Chercher une mise à jour") }
                }
                Text(
                    "Les données (livre de recettes, réglages, modèle) sont conservées. Android demande une confirmation avant d'installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(
                "Mes courses · version 0.3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable {
                    versionTaps++
                    if (versionTaps >= 7) { versionTaps = 0; onDebug() }
                }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


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
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.cookbook

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.EmptyState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.core.agent.DateResolver
import fr.mescourses.core.model.MealSlot
import fr.mescourses.core.shopping.ShoppingListMerger
import java.time.LocalDate

@Composable
fun CookbookScreen(
    importUrl: String?,
    onImportConsumed: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onNewRecipe: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = containerViewModel { CookbookViewModel(it) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }

    LaunchedEffect(importUrl) {
        if (importUrl != null) {
            vm.import(importUrl) { onOpenRecipe(it) }
            onImportConsumed()
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    ScreenScaffold(
        title = "Livre de recettes",
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { showImport = true }) { Icon(Icons.Filled.Link, "Importer depuis une adresse") }
                FloatingActionButton(onClick = onNewRecipe) { Icon(Icons.Filled.Add, "Nouvelle recette") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Chercher une recette") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (importing) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp)); Text("Import en cours…", Modifier.padding(start = 8.dp))
            }
            if (recipes.isEmpty()) {
                EmptyState(
                    "Ton livre de recettes est vide",
                    "Demande des idées à l'assistant, importe une page (bouton lien, ou « Partager » depuis le navigateur), ou écris ta recette.",
                )
            } else {
                LazyColumn {
                    items(recipes, key = { it.id }) { r ->
                        ListItem(
                            headlineContent = { Text(r.title) },
                            supportingContent = {
                                Text(listOfNotNull(
                                    "${r.servings} pers.",
                                    r.cookMinutes?.let { "${(r.prepMinutes ?: 0) + it} min" },
                                    r.tags.take(3).joinToString(", ").ifEmpty { null },
                                ).joinToString(" · "))
                            },
                            trailingContent = { if (r.favorite) Icon(Icons.Filled.Star, "Favori") },
                            modifier = Modifier.clickable { onOpenRecipe(r.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showImport) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Importer une recette") },
            text = {
                Column {
                    Text("Colle l'adresse d'une page (Marmiton, 750g, Cuisine AZ…).", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("https://…") })
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http"), onClick = { vm.import(url) { onOpenRecipe(it) }; showImport = false }) { Text("Importer") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Annuler") } },
        )
    }
}

@Composable
fun RecipeDetailScreen(recipeId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val vm = containerViewModel(key = "recipe-$recipeId") { RecipeDetailViewModel(it, recipeId) }
    val recipe by vm.recipe.collectAsStateWithLifecycle()
    val servingsOverride by vm.servings.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showPlan by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    val r = recipe
    ScreenScaffold(
        title = r?.title ?: "Recette",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (r != null) {
                IconButton(onClick = { vm.toggleFavorite(r) }) {
                    Icon(if (r.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favori")
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Modifier") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") }
            }
        },
    ) { padding ->
        if (r == null) return@ScreenScaffold
        val servings = servingsOverride ?: vm.servingsOrDefault(r)
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
        ) {
            Text(
                listOfNotNull(
                    r.prepMinutes?.let { "Préparation $it min" },
                    r.cookMinutes?.let { "Cuisson $it min" },
                    r.tags.joinToString(", ").ifEmpty { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pour", Modifier.padding(end = 8.dp))
                IconButton(onClick = { vm.servings.value = (servings - 1).coerceAtLeast(1) }) { Icon(Icons.Filled.Remove, "Moins") }
                Text("$servings pers.", fontWeight = FontWeight.Bold)
                IconButton(onClick = { vm.servings.value = servings + 1 }) { Icon(Icons.Filled.Add, "Plus") }
            }
            Text("Ingrédients", style = MaterialTheme.typography.titleMedium)
            ShoppingListMerger.scale(r, servings).forEach { ing ->
                val q = ing.quantity?.let { ShoppingListMerger.formatNumber(it) }
                Text("• " + listOfNotNull(q, ing.unit, ing.name).joinToString(" "), Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addToList(r) }) { Text("Ajouter à la liste") }
                OutlinedButton(onClick = { showPlan = true }) { Text("Planifier") }
            }
            Spacer(Modifier.size(16.dp))
            Text("Préparation", style = MaterialTheme.typography.titleMedium)
            r.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", Modifier.padding(vertical = 4.dp)) }
            r.sourceUrl?.let { url ->
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text("Voir la source", Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (showPlan && r != null) PlanDialog(onDismiss = { showPlan = false }) { date, slot -> vm.plan(r, date, slot); showPlan = false }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer cette recette ?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

/** Choix d'une date (14 prochains jours) et d'un repas. */
@Composable
fun PlanDialog(onDismiss: () -> Unit, onConfirm: (LocalDate, MealSlot) -> Unit) {
    val days = remember { (0L until 14L).map { LocalDate.now().plusDays(it) } }
    var date by remember { mutableStateOf(days.first()) }
    var slot by remember { mutableStateOf(MealSlot.DINER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Planifier") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealSlot.entries.forEach { s -> FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(s.label) }) }
                }
                LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 320.dp)) {
                    items(days) { d ->
                        ListItem(
                            headlineContent = { Text(DateResolver.label(d), fontWeight = if (d == date) FontWeight.Bold else null) },
                            modifier = Modifier.clickable { date = d },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(date, slot) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun RecipeEditScreen(recipeId: Long, onDone: () -> Unit) {
    val vm = containerViewModel(key = "edit-$recipeId") { RecipeEditViewModel(it, recipeId) }
    val title by vm.title.collectAsStateWithLifecycle()
    val servings by vm.servings.collectAsStateWithLifecycle()
    val ingredients by vm.ingredients.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = if (recipeId == 0L) "Nouvelle recette" else "Modifier",
        onBack = onDone,
        actions = { TextButton(onClick = { vm.save(onDone) }, enabled = title.isNotBlank()) { Text("Enregistrer") } },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(title, { vm.title.value = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                servings, { vm.servings.value = it.filter(Char::isDigit) }, label = { Text("Personnes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                ingredients, { vm.ingredients.value = it }, minLines = 5, modifier = Modifier.fillMaxWidth(),
                label = { Text("Ingrédients (un par ligne : « 200 g de farine »)") },
            )
            OutlinedTextField(steps, { vm.steps.value = it }, minLines = 5, label = { Text("Étapes (une par ligne)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tags, { vm.tags.value = it }, label = { Text("Tags (séparés par des virgules)") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

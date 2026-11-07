@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package fr.mescourses.app.ui.shopping

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.BottomActionBar
import fr.mescourses.app.ui.components.EmptyState
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.core.model.ItemSource
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.Aisle
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Une ligne affichée : un produit (fusion de tous les articles de même nom). */
data class ShoppingLine(
    val key: String,
    val label: String,
    val quantity: String,
    val origin: String?,
    val checked: Boolean,
    val aisle: Aisle,
    val sources: List<ShoppingItem>,
    /** Quantité unique proposée dans la feuille d'édition (null si plusieurs unités incompatibles). */
    val editQuantity: Double? = null,
    val editUnit: String? = null,
)

/** Unités proposées en un toucher dans la feuille d'édition (null = pièce). */
private val EDIT_UNITS = listOf<String?>(null, "g", "kg", "ml", "l")

class ShoppingViewModel(private val c: AppContainer) : ViewModel() {

    val lines: StateFlow<List<ShoppingLine>> = c.shopping.observeAll().map { items ->
        items.groupBy { if (ShoppingListMerger.isEgg(it.name)) "oeuf" else ShoppingListMerger.key(it.name) }.map { (key, group) ->
            val merged = ShoppingListMerger.merge(group.map { it.copy(checked = false) })
            val first = merged.first()
            ShoppingLine(
                key = key,
                label = first.name,
                quantity = merged.map { m ->
                    listOfNotNull(m.quantity?.let { ShoppingListMerger.formatNumber(it) }, m.unit).joinToString(" ")
                }.filter { it.isNotBlank() }.joinToString(" + "),
                origin = group.mapNotNull { it.origin }.distinct().joinToString(", ").ifEmpty { null },
                checked = group.all { it.checked },
                aisle = Aisle.of(first.name),
                sources = group,
                editQuantity = merged.singleOrNull()?.quantity,
                editUnit = merged.singleOrNull()?.unit,
            )
        }.sortedWith(compareBy({ it.checked }, { it.aisle.ordinal }, { it.label.lowercase() }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(text: String) = viewModelScope.launch {
        text.split('\n', ';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { lines -> c.shopping.add(lines.map(ShoppingListMerger::fromText)) }
    }

    fun toggle(line: ShoppingLine) = viewModelScope.launch {
        line.sources.forEach { c.shopping.update(it.copy(checked = !line.checked)) }
    }

    fun delete(line: ShoppingLine) = viewModelScope.launch { line.sources.forEach { c.shopping.delete(it.id) } }

    /**
     * Modifie un article : un seul article d'origine est mis à jour ; une ligne fusionnée (plusieurs recettes)
     * est remplacée par un seul article, qui garde l'origine affichée.
     */
    fun edit(line: ShoppingLine, name: String, quantity: Double?, unit: String?) = viewModelScope.launch {
        val clean = name.trim().ifEmpty { return@launch }
        val single = line.sources.singleOrNull()
        if (single != null) {
            c.shopping.update(single.copy(name = clean, quantity = quantity, unit = unit))
        } else {
            line.sources.forEach { c.shopping.delete(it.id) }
            c.shopping.add(
                listOf(
                    ShoppingItem(
                        name = clean, quantity = quantity, unit = unit, origin = line.origin, checked = line.checked,
                        source = if (line.sources.any { it.source == ItemSource.RECETTE }) ItemSource.RECETTE else ItemSource.MANUEL,
                    ),
                ),
            )
        }
    }

    /** Envoie les articles restants à l'écran Panier Carrefour. */
    fun sendToCart() = viewModelScope.launch {
        c.pendingCart.value = fr.mescourses.app.PendingCart("Liste de courses", c.shopping.all().filterNot { it.checked })
    }

    fun restore(items: List<ShoppingItem>) = viewModelScope.launch { c.shopping.add(items) }

    /** Supprime les articles pris et renvoie ce qui a été supprimé (pour « Annuler »). */
    suspend fun clearChecked(): List<ShoppingItem> {
        val removed = c.shopping.all().filter { it.checked }
        c.shopping.clearChecked()
        return removed
    }
}

@Composable
fun ShoppingListScreen(onOpenSettings: () -> Unit, onSendToCart: () -> Unit) {
    val vm = containerViewModel { ShoppingViewModel(it) }
    val lines by vm.lines.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ShoppingLine?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val done = lines.count { it.checked }
    val progress by animateFloatAsState(if (lines.isEmpty()) 0f else done.toFloat() / lines.size, label = "progress")

    fun undoable(message: String, removed: List<ShoppingItem>) = scope.launch {
        if (removed.isEmpty()) return@launch
        if (snackbar.showSnackbar(message, actionLabel = "Annuler") == SnackbarResult.ActionPerformed) vm.restore(removed)
    }

    ScreenScaffold(
        title = "Liste de courses",
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
        actions = {
            if (lines.any { !it.checked }) IconButton(onClick = { vm.sendToCart(); onSendToCart() }) {
                Icon(Icons.Filled.ShoppingCart, "Envoyer au panier Carrefour")
            }
            if (done > 0) IconButton(onClick = { scope.launch { undoable("$done article(s) retiré(s)", vm.clearChecked()) } }) {
                Icon(Icons.Filled.DeleteSweep, "Retirer les articles pris")
            }
        },
        bottomBar = {
            BottomActionBar(Modifier.imePadding()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ajouter : lessive, 2 kg de pommes…") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.add(input); input = "" }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { vm.add(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.Filled.Add, "Ajouter") }
            }
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState("🧺", "Ta liste est vide", "Valide un menu, ajoute une recette, ou tape un article en bas de l'écran.")
            }
            return@ScreenScaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$done / ${lines.size} dans le panier", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (done == lines.size) Text("🎉")
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
                }
            }
            val todo = lines.filterNot { it.checked }
            todo.groupBy { it.aisle }.toSortedMap(compareBy { it.ordinal }).forEach { (aisle, group) ->
                item(key = "h-${aisle.name}") {
                    Text(
                        "${aisle.emoji}  ${aisle.label}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp).animateItem(),
                    )
                }
                items(group, key = { it.key }) { line ->
                    ShoppingRow(line, onToggle = { vm.toggle(line) }, onEdit = { editing = line }, modifier = Modifier.animateItem())
                }
            }
            val taken = lines.filter { it.checked }
            if (taken.isNotEmpty()) {
                item(key = "h-done") {
                    Text(
                        "✅  Déjà pris",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp).animateItem(),
                    )
                }
                items(taken, key = { "d-" + it.key }) { line ->
                    ShoppingRow(line, onToggle = { vm.toggle(line) }, onEdit = { editing = line }, modifier = Modifier.animateItem())
                }
            }
        }
    }

    editing?.let { line ->
        EditItemSheet(
            line = line,
            onDismiss = { editing = null },
            onSave = { name, q, u -> vm.edit(line, name, q, u); editing = null },
            onDelete = {
                vm.delete(line); editing = null
                undoable("« ${line.label} » supprimé", line.sources)
            },
        )
    }
}

/** Feuille « Modifier l'article » : nom, quantité, unité ; Supprimer / Enregistrer. */
@Composable
private fun EditItemSheet(line: ShoppingLine, onDismiss: () -> Unit, onSave: (String, Double?, String?) -> Unit, onDelete: () -> Unit) {
    var name by remember(line.key) { mutableStateOf(line.label) }
    var quantity by remember(line.key) { mutableStateOf(line.editQuantity?.let { ShoppingListMerger.formatNumber(it) } ?: "") }
    var unit by remember(line.key) { mutableStateOf(line.editUnit) }
    val units = remember(line.key) { (EDIT_UNITS + line.editUnit).distinct() }
    val parsed = quantity.trim().replace(',', '.').toDoubleOrNull()
    val quantityOk = quantity.isBlank() || (parsed != null && parsed > 0)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Modifier l'article", style = MaterialTheme.typography.titleLarge)
            if (line.sources.size > 1) {
                Text(
                    "Cette ligne regroupe ${line.sources.size} articles (${line.origin ?: "liste"}) : ils seront remplacés par un seul.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (line.quantity.isNotBlank() && line.editQuantity == null) {
                Text("Quantité actuelle : ${line.quantity}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Produit") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = quantity, onValueChange = { quantity = it },
                label = { Text("Quantité") }, singleLine = true, isError = !quantityOk,
                placeholder = { Text("vide = sans quantité") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                units.forEach { u ->
                    FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u ?: "pièce") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.DeleteOutline, null)
                    Text("Supprimer", Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = { onSave(name, parsed, if (parsed == null) null else unit) },
                    enabled = name.isNotBlank() && quantityOk,
                    modifier = Modifier.weight(1f),
                ) { Text("Enregistrer") }
            }
        }
    }
}


@Composable
private fun ShoppingRow(line: ShoppingLine, onToggle: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
        Row(
            modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable(onClick = onEdit)
                .padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = line.checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    line.label,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                    color = if (line.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                line.origin?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (line.quantity.isNotBlank()) {
                Text(
                    line.quantity,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 13.sp,
                )
            }
        }
}

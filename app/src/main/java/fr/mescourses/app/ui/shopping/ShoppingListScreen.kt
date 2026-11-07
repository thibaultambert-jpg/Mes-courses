@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.shopping

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.ui.common.EmptyState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.core.model.ItemSource
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShoppingViewModel(private val c: AppContainer) : ViewModel() {
    val items: StateFlow<List<ShoppingItem>> = c.shopping.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(text: String) = viewModelScope.launch {
        text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { lines -> c.shopping.add(lines.map(ShoppingListMerger::fromText)) }
    }
    fun toggle(item: ShoppingItem) = viewModelScope.launch { c.shopping.update(item.copy(checked = !item.checked)) }
    fun delete(item: ShoppingItem) = viewModelScope.launch { c.shopping.delete(item.id) }
    fun clearChecked() = viewModelScope.launch { c.shopping.clearChecked() }
}

@Composable
fun ShoppingListScreen(onOpenSettings: () -> Unit) {
    val vm = containerViewModel { ShoppingViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    var merged by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    ScreenScaffold(
        title = "Liste de courses",
        onOpenSettings = onOpenSettings,
        actions = { IconButton(onClick = vm::clearChecked) { Icon(Icons.Filled.DeleteSweep, "Supprimer les articles cochés") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ajouter : « lessive », « 2 kg de pommes »") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.add(input); input = "" }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.add(input); input = "" }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Ajouter")
                }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                FilterChip(selected = !merged, onClick = { merged = false }, label = { Text("Détail") }, modifier = Modifier.padding(end = 8.dp))
                FilterChip(selected = merged, onClick = { merged = true }, label = { Text("Fusionnée (pour le panier)") })
            }
            if (items.isEmpty()) {
                EmptyState("Liste vide", "Ajoute des articles ci-dessus, ou depuis une recette ou en validant un menu.")
                return@Column
            }
            LazyColumn {
                if (merged) {
                    items(ShoppingListMerger.merge(items)) { item ->
                        ListItem(
                            headlineContent = { Text(ShoppingListMerger.format(item)) },
                            supportingContent = {
                                item.origin?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            },
                        )
                        HorizontalDivider()
                    }
                } else {
                    items(items, key = { it.id }) { item ->
                        ListItem(
                            leadingContent = { Checkbox(checked = item.checked, onCheckedChange = { vm.toggle(item) }) },
                            headlineContent = {
                                Text(
                                    ShoppingListMerger.format(item),
                                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (item.source == ItemSource.RECETTE) "Recette : ${item.origin.orEmpty()}" else "Ajout manuel",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = { IconButton(onClick = { vm.delete(item) }) { Icon(Icons.Filled.Close, "Retirer") } },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

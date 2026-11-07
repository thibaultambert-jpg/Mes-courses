@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.cart

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.mcp.Product
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.shopping.ShoppingListMerger

@Composable
fun CartScreen(onOpenSettings: () -> Unit) {
    val vm = containerViewModel { CartViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ScreenScaffold(title = "Panier drive", onOpenSettings = onOpenSettings) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp)) {
                Retailer.entries.forEach { r ->
                    FilterChip(
                        selected = state.retailer == r,
                        onClick = { vm.selectRetailer(r) },
                        label = { Text(r.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            if (!vm.isConfigured(state.retailer)) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Aucun serveur MCP n'est configuré pour ${state.retailer.label}. " +
                                "Renseigne l'adresse de ton hôte MCP (voir server/README.md) dans les Réglages.",
                        )
                        TextButton(onClick = onOpenSettings) { Text("Ouvrir les réglages") }
                    }
                }
                return@Column
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }

            when (state.phase) {
                Phase.IDLE -> Column(Modifier.padding(12.dp)) {
                    Text(
                        "L'application va chercher chaque article de ta liste (fusionnée) chez ${state.retailer.label}, " +
                            "te proposer un produit, puis l'ajouter au panier après ta validation. " +
                            "La commande et le paiement se font ensuite chez l'enseigne.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.padding(top = 12.dp)) {
                        Button(onClick = vm::prepare) { Text("Préparer le panier") }
                        OutlinedButton(onClick = { vm.readCart() }, modifier = Modifier.padding(start = 8.dp)) { Text("Voir le panier") }
                    }
                }
                Phase.CONNEXION -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Text("Connexion au serveur MCP…", Modifier.padding(start = 8.dp))
                }
                else -> {
                    val done = state.lines.count { it.status != LineStatus.A_CHERCHER }
                    if (state.phase == Phase.RECHERCHE) {
                        LinearProgressIndicator(progress = { done.toFloat() / state.lines.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                    val selected = state.lines.count { it.included && it.product != null && it.status != LineStatus.AJOUTE }
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (state.phase) {
                            Phase.VALIDATION -> Button(onClick = vm::send, enabled = selected > 0) { Text("Ajouter $selected produit(s) au panier") }
                            Phase.ENVOI -> { CircularProgressIndicator(Modifier.size(18.dp)); Text("Ajout en cours…", Modifier.padding(start = 8.dp)) }
                            Phase.TERMINE -> {
                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.retailer.cartUrl))) }) {
                                    Text("Finaliser chez ${state.retailer.label}")
                                }
                                TextButton(onClick = { vm.markSentAsBought() }) { Text("Cocher dans la liste") }
                            }
                            else -> Unit
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.lines) { index, line ->
                            CartLineRow(
                                line = line,
                                editable = state.phase == Phase.VALIDATION,
                                onChange = { transform -> vm.updateLine(index, transform) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    state.cartText?.let { text ->
        AlertDialog(
            onDismissRequest = vm::dismissCart,
            title = { Text("Panier ${state.retailer.label}") },
            text = { Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = vm::dismissCart) { Text("Fermer") } },
        )
    }
}

@Composable
private fun CartLineRow(line: CartLine, editable: Boolean, onChange: ((CartLine) -> CartLine) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = line.included,
            enabled = editable && line.product != null,
            onCheckedChange = { checked -> onChange { it.copy(included = checked) } },
        )
        Column(Modifier.weight(1f)) {
            Text(ShoppingListMerger.format(line.item), fontWeight = FontWeight.SemiBold)
            when (line.status) {
                LineStatus.A_CHERCHER -> Text("Recherche…", style = MaterialTheme.typography.bodySmall)
                LineStatus.INTROUVABLE -> Text("Aucun produit trouvé", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                LineStatus.ERREUR -> Text(line.message ?: "Erreur", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                else -> line.product?.let { p ->
                    Box {
                        TextButton(onClick = { menu = true }, enabled = editable && line.candidates.size > 1) {
                            Text(describe(p), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            line.candidates.forEachIndexed { i, candidate ->
                                DropdownMenuItem(
                                    text = { Text(describe(candidate)) },
                                    onClick = { onChange { it.copy(selected = i) }; menu = false },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (line.product != null) {
            if (editable) {
                IconButton(onClick = { onChange { it.copy(quantity = (it.quantity - 1).coerceAtLeast(1)) } }) { Icon(Icons.Filled.Remove, "Moins") }
                Text("${line.quantity}")
                IconButton(onClick = { onChange { it.copy(quantity = it.quantity + 1) } }) { Icon(Icons.Filled.Add, "Plus") }
            } else {
                Text("×${line.quantity}", Modifier.padding(horizontal = 8.dp))
            }
        }
        when (line.status) {
            LineStatus.AJOUTE -> Icon(Icons.Filled.CheckCircle, "Ajouté", tint = MaterialTheme.colorScheme.primary)
            LineStatus.ERREUR -> Icon(Icons.Filled.Error, "Erreur", tint = MaterialTheme.colorScheme.error)
            LineStatus.INTROUVABLE -> Icon(Icons.Filled.SearchOff, "Introuvable")
            else -> Unit
        }
    }
}

private fun describe(p: Product): String = listOfNotNull(
    p.name,
    p.brand?.takeIf { !p.name.contains(it, ignoreCase = true) },
    p.packaging,
    p.price?.let { "%.2f €".format(it) },
    p.unitPrice,
).joinToString(" · ")

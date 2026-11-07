@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.cart

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.BottomActionBar
import fr.mescourses.app.ui.components.EmptyState
import fr.mescourses.app.ui.components.MiniStepper
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.StepIndicator
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.mcp.Product
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.shopping.ShoppingListMerger

private val Retailer.color get() = when (this) {
    Retailer.CARREFOUR -> Color(0xFF1E5BC6)
    Retailer.LECLERC -> Color(0xFF0066B3)
    Retailer.AUCHAN -> Color(0xFFD6232A)
}
private val Retailer.initials get() = when (this) {
    Retailer.CARREFOUR -> "C"
    Retailer.LECLERC -> "E.L"
    Retailer.AUCHAN -> "A"
}

private fun Product.isOrganic() = Regex("""\bbio\b""", RegexOption.IGNORE_CASE).containsMatchIn(name + " " + (brand ?: ""))
private fun euros(v: Double) = String.format(java.util.Locale.FRANCE, "%.2f €", v)

@Composable
fun CartScreen(onOpenSettings: () -> Unit) {
    val vm = containerViewModel { CartViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var alternativesFor by remember { mutableStateOf<Int?>(null) }
    val stepIndex = when (state.phase) {
        Phase.IDLE, Phase.CONNEXION, Phase.RECHERCHE -> 0
        Phase.VALIDATION -> 1
        Phase.ENVOI, Phase.TERMINE -> 2
    }
    val selected = state.lines.filter { it.included && it.product != null && it.status != LineStatus.AJOUTE }
    val total = state.lines.filter { it.included && it.product != null }.sumOf { (it.product?.price ?: 0.0) * it.quantity }

    ScreenScaffold(
        title = "Panier drive",
        onOpenSettings = onOpenSettings,
        bottomBar = {
            when (state.phase) {
                Phase.VALIDATION -> BottomActionBar {
                    Column(Modifier.weight(1f)) {
                        Text("Total estimé", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(euros(total), style = MaterialTheme.typography.titleLarge)
                    }
                    Button(onClick = vm::send, enabled = selected.isNotEmpty()) { Text("Envoyer ${selected.size} produits") }
                }
                Phase.TERMINE -> BottomActionBar {
                    OutlinedButton(onClick = vm::markSentAsBought) { Text("Cocher dans la liste") }
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.retailer.cartUrl))) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Finaliser chez ${state.retailer.label}") }
                }
                else -> Unit
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Retailer.entries.forEach { r ->
                        RetailerCard(r, selected = state.retailer == r, configured = vm.isConfigured(r), modifier = Modifier.weight(1f)) {
                            vm.selectRetailer(r)
                        }
                    }
                }
            }
            if (!vm.isConfigured(state.retailer)) {
                item {
                    EmptyState(
                        "🔌", "${state.retailer.label} n'est pas encore relié",
                        "Indique l'adresse de ton serveur MCP dans les Réglages (voir server/README.md).",
                        actionLabel = "Ouvrir les réglages", onAction = onOpenSettings,
                    )
                }
                return@LazyColumn
            }
            item {
                StepIndicator(
                    steps = listOf("Préparer", "Vérifier", "Envoyer"),
                    current = stepIndex,
                    onStep = {},
                    enabled = { false },
                )
            }
            state.error?.let { err ->
                item { Text("⚠ $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            when (state.phase) {
                Phase.IDLE -> item {
                    SectionCard {
                        Text("🛒 Remplir mon panier", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Je cherche chaque article de ta liste chez ${state.retailer.label} (en bio quand c'est possible), " +
                                "tu vérifies, puis je l'ajoute au panier. La commande et le paiement restent chez l'enseigne.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::prepare) { Text("Préparer le panier") }
                            OutlinedButton(onClick = { vm.readCart() }) { Text("Voir le panier") }
                        }
                    }
                }
                Phase.CONNEXION -> item { Busy("Connexion à ${state.retailer.label}…") }
                Phase.TERMINE -> item {
                    SectionCard {
                        Text("🎉 Panier rempli !", style = MaterialTheme.typography.titleLarge)
                        val added = state.lines.count { it.status == LineStatus.AJOUTE }
                        Text("$added produit(s) ajoutés. Il ne reste qu'à choisir le créneau et payer sur ${state.retailer.label}.")
                    }
                }
                else -> Unit
            }
            if (state.phase == Phase.RECHERCHE) {
                item {
                    val done = state.lines.count { it.status != LineStatus.A_CHERCHER }
                    Column {
                        Text("Recherche des produits… $done / ${state.lines.size}", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { done.toFloat() / state.lines.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(50)),
                        )
                    }
                }
            }
            if (state.phase == Phase.ENVOI) item { Busy("Ajout au panier, à rythme humain…") }
            if (state.phase != Phase.IDLE && state.phase != Phase.CONNEXION) {
                itemsIndexed(state.lines) { index, line ->
                    CartLineCard(
                        line = line,
                        editable = state.phase == Phase.VALIDATION,
                        onChange = { t -> vm.updateLine(index, t) },
                        onAlternatives = { alternativesFor = index },
                    )
                }
            }
        }
    }

    alternativesFor?.let { index ->
        val line = state.lines.getOrNull(index)
        if (line != null) {
            ModalBottomSheet(onDismissRequest = { alternativesFor = null }) {
                Text(
                    "Choisir pour « ${ShoppingListMerger.format(line.item)} »",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                line.candidates.forEachIndexed { i, p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(listOfNotNull(p.brand, p.packaging, p.unitPrice).joinToString(" · ")) },
                        trailingContent = { p.price?.let { Text(euros(it), style = MaterialTheme.typography.titleSmall) } },
                        leadingContent = { if (p.isOrganic()) BioBadge() else if (i == line.selected) Icon(Icons.Filled.CheckCircle, null) },
                        modifier = Modifier.clickable { vm.updateLine(index) { it.copy(selected = i) }; alternativesFor = null },
                    )
                }
                Spacer(Modifier.size(24.dp))
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
private fun RetailerCard(r: Retailer, selected: Boolean, configured: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(r.color), contentAlignment = Alignment.Center) {
                Text(r.initials, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Text(r.label.removeSuffix(" Drive"), style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    if (configured) "relié" else "à configurer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun BioBadge() {
    Text(
        "BIO",
        fontSize = 10.sp,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF5E9B3A)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CartLineCard(line: CartLine, editable: Boolean, onChange: ((CartLine) -> CartLine) -> Unit, onAlternatives: () -> Unit) {
    val product = line.product
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (line.included || line.status == LineStatus.AJOUTE) MaterialTheme.colorScheme.surfaceContainerLowest
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = line.included,
                enabled = editable && product != null,
                onCheckedChange = { checked -> onChange { it.copy(included = checked) } },
            )
            Column(Modifier.weight(1f)) {
                Text(ShoppingListMerger.format(line.item), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (line.status) {
                    LineStatus.A_CHERCHER -> Text("Recherche…", style = MaterialTheme.typography.bodyMedium)
                    LineStatus.INTROUVABLE -> Text("Aucun produit trouvé", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    LineStatus.ERREUR -> Text(line.message ?: "Erreur", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    else -> product?.let { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.isOrganic()) { BioBadge(); Spacer(Modifier.size(6.dp)) }
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            listOfNotNull(p.brand, p.unitPrice).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (editable && line.candidates.size > 1) {
                            TextButton(onClick = onAlternatives, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp))
                                Text(" ${line.candidates.size - 1} autre(s) choix", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                product?.price?.let { Text(euros(it * line.quantity), style = MaterialTheme.typography.titleSmall) }
                when (line.status) {
                    LineStatus.AJOUTE -> Icon(Icons.Filled.CheckCircle, "Ajouté", tint = MaterialTheme.colorScheme.primary)
                    LineStatus.ERREUR -> Icon(Icons.Filled.ErrorOutline, "Erreur", tint = MaterialTheme.colorScheme.error)
                    else -> if (product != null && editable) {
                        MiniStepper("×", line.quantity, { q -> onChange { it.copy(quantity = q.coerceAtLeast(1)) } }, true, "Quantité")
                    }
                }
            }
        }
    }
}

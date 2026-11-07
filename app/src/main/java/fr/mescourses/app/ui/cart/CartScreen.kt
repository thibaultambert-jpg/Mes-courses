@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.cart

import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.ui.components.ChefCard
import fr.mescourses.app.ui.components.ModelLoadingCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.carrefour.SessionState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.BottomActionBar
import fr.mescourses.app.ui.components.MiniStepper
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.app.ui.components.StepIndicator
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.shopping.ShoppingListMerger

private val CarrefourBlue = Color(0xFF1E5BC6)
private fun euros(v: Double) = String.format(java.util.Locale.FRANCE, "%.2f €", v)

@Composable
fun CartScreen(
    onOpenSettings: () -> Unit,
    onLogin: () -> Unit,
    onOpenSite: (String) -> Unit,
    onChooseStore: () -> Unit,
) {
    val vm = containerViewModel { CartViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var alternativesFor by remember { mutableStateOf<Int?>(null) }
    var chatOpen by remember { mutableStateOf(false) }
    val stepIndex = when (state.phase) {
        Phase.IDLE, Phase.RECHERCHE -> 0
        Phase.VALIDATION -> 1
        Phase.ENVOI, Phase.TERMINE -> 2
    }
    val selected = state.lines.filter { it.included && it.product != null && it.status != LineStatus.AJOUTE }
    val total = state.lines.filter { it.included && it.product != null }.sumOf { (it.product?.price ?: 0.0) * it.quantity }

    ScreenScaffold(
        title = "Panier Carrefour",
        onOpenSettings = onOpenSettings,
        bottomBar = {
            when (state.phase) {
                Phase.VALIDATION -> BottomActionBar {
                    Column(Modifier.weight(1f)) {
                        Text("Total estimé", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(euros(total), style = MaterialTheme.typography.titleLarge)
                    }
                    Button(onClick = vm::send, enabled = selected.isNotEmpty()) { Text("Ajouter ${selected.size} produits") }
                }
                Phase.TERMINE -> BottomActionBar {
                    OutlinedButton(onClick = vm::markSentAsBought) { Text("Cocher dans la liste") }
                    Button(onClick = { onOpenSite(fr.mescourses.core.carrefour.CarrefourRoutes.WEB_CART) }, modifier = Modifier.weight(1f)) {
                        Text("Finaliser sur Carrefour")
                    }
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
                AccountCard(session, settings.storeName, onLogin = onLogin, onChooseStore = onChooseStore)
            }
            if (session != SessionState.CONNECTE) {
                if (session == SessionState.VERIFICATION) item {
                    SectionCard {
                        Text("Carrefour demande une vérification anti-robot.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onOpenSite(fr.mescourses.core.carrefour.CarrefourRoutes.PARKING) }) { Text("Ouvrir carrefour.fr") }
                    }
                }
                return@LazyColumn
            }
            item {
                StepIndicator(steps = listOf("Préparer", "Vérifier", "Envoyer"), current = stepIndex, onStep = {}, enabled = { false })
            }
            state.error?.let { err -> item { Text("⚠ $err", color = MaterialTheme.colorScheme.error) } }
            when (state.phase) {
                Phase.IDLE -> item {
                    SectionCard {
                        Text("🛒 Remplir mon panier", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Je propose un produit par article de ta liste (bio quand c'est possible, et tes choix précédents en priorité). " +
                                "Tu vérifies, puis j'ajoute tout au panier. La commande et le paiement se font ensuite sur carrefour.fr.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.prepare() }) { Text("Depuis ma liste") }
                            OutlinedButton(onClick = { chatOpen = true }) { Text("💬 Avec l'assistant") }
                            TextButton(onClick = { vm.loadInfo() }) { Text("Voir mon panier") }
                        }
                    }
                }
                Phase.TERMINE -> item {
                    SectionCard {
                        when {
                            state.noDrive -> {
                                Text("🏪 Drive pas encore reconnu", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Carrefour ne m'a pas encore donné l'identifiant de ton drive : il apparaît dans ton panier dès qu'il contient " +
                                        "un article, ou dans les offres d'une recherche. Ajoute un premier article sur le site, puis réessaie.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onOpenSite(CarrefourRoutes.ORIGIN + CarrefourRoutes.search("lait")) }) { Text("Ajouter sur le site") }
                                    Button(onClick = { vm.send() }) { Text("Réessayer") }
                                }
                            }
                            state.added == 0 -> {
                                Text("Aucun produit ajouté", style = MaterialTheme.typography.titleLarge)
                                Text("Regarde le message sous chaque article, puis réessaie.", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { vm.send() }) { Text("Réessayer") }
                            }
                            else -> {
                                Text("🎉 Panier rempli !", style = MaterialTheme.typography.titleLarge)
                                Text("${state.added} produit(s) ajoutés. Choisis ton créneau et paie sur carrefour.fr.")
                            }
                        }
                    }
                }
                else -> Unit
            }
            if (state.phase == Phase.RECHERCHE) {
                item {
                    val done = state.lines.count { it.status != LineStatus.A_CHERCHER }
                    Column {
                        Text("${state.source} : recherche des produits… $done / ${state.lines.size}", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { done.toFloat() / state.lines.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(50)),
                        )
                    }
                }
            }
            if (state.phase == Phase.ENVOI) item { Busy("Ajout au panier, à rythme humain…") }
            if (state.phase != Phase.IDLE) {
                itemsIndexed(state.lines) { index, line ->
                    CartLineCard(
                        line = line,
                        editable = state.phase == Phase.VALIDATION,
                        onChange = { t -> vm.updateLine(index, t) },
                        onAlternatives = { if (line.remembered) vm.moreChoices(index) else alternativesFor = index },
                    )
                }
            }
            if (state.loadingInfo) item { Busy("Lecture du panier…") }
            state.cart?.let { cart -> item { CartSummary(cart, state.slots) } }
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
                        supportingContent = { Text(listOfNotNull(p.brand, p.unitPrice, p.nutriscore?.let { "Nutri-Score $it" }, p.promo).joinToString(" · ")) },
                        trailingContent = { p.price?.let { Text(euros(it), style = MaterialTheme.typography.titleSmall) } },
                        leadingContent = { if (p.organic) BioBadge() else if (i == line.selected) Icon(Icons.Filled.CheckCircle, null) },
                        modifier = Modifier.clickable { vm.updateLine(index) { it.copy(selected = i) }; alternativesFor = null },
                    )
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    if (chatOpen) ShoppingChatSheet(onDismiss = { chatOpen = false })
}

@Composable
private fun AccountCard(session: SessionState, storeName: String, onLogin: () -> Unit, onChooseStore: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(CarrefourBlue), contentAlignment = Alignment.Center) {
                Text("C", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Carrefour Drive", style = MaterialTheme.typography.titleMedium)
                Text(storeName.ifBlank { "Aucun drive choisi" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatChip(session.label, emphasized = session != SessionState.CONNECTE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session == SessionState.CONNEXION_REQUISE || session == SessionState.INCONNU) Button(onClick = onLogin) { Text("Se connecter") }
            OutlinedButton(onClick = onChooseStore) { Text(if (storeName.isBlank()) "Choisir mon drive" else "Changer de drive") }
        }
    }
}

@Composable
private fun CartSummary(cart: fr.mescourses.core.carrefour.Cart, slots: List<fr.mescourses.core.carrefour.TimeSlot>?) {
    SectionCard {
        Text("Mon panier Carrefour", style = MaterialTheme.typography.titleMedium)
        if (cart.lines.isEmpty()) Text("Panier vide.", style = MaterialTheme.typography.bodyMedium)
        cart.lines.take(30).forEach { l ->
            Row {
                Text("${l.quantity} × ${l.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.subtotal?.let { Text(euros(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        cart.total?.let { Text("Total : ${euros(it)}", style = MaterialTheme.typography.titleSmall) }
        if (!slots.isNullOrEmpty()) {
            Text("Prochains créneaux (à réserver sur le site)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            slots.forEach { s ->
                Text("• ${s.date} ${s.start}–${s.end}" + (s.price?.let { " · ${euros(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Feuille « Courses avec l'assistant » : Gemma cherche, propose au plus 3 produits, et chaque ajout est confirmé. */
@Composable
private fun ShoppingChatSheet(onDismiss: () -> Unit) {
    val vm = containerViewModel { ShoppingChatViewModel(it) }
    val lines by vm.lines.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp)) {
            Text("Courses avec l'assistant", style = MaterialTheme.typography.titleLarge)
            Text(
                if (model.available) "Demande en français ; tu confirmes chaque ajout." else "Installe l'assistant dans les Réglages pour utiliser ce mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                model == ModelStatus.Loading -> ModelLoadingCard(Modifier.padding(top = 8.dp))
                busy -> ChefCard("Le chef réfléchit…", "Il cherche les produits au drive Carrefour.", Modifier.padding(top = 8.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines) { l ->
                    if (l.tool) {
                        Text(l.text, style = MaterialTheme.typography.labelMedium, color = if (l.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.fromUser) Arrangement.End else Arrangement.Start) {
                            Text(
                                l.text.trim(),
                                color = if (l.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                                    .background(if (l.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("De quoi faire une pâte à crêpes pour 4", "Qu'y a-t-il dans mon panier ?", "Prochains créneaux ?").forEach { s ->
                    SuggestionChip(onClick = { input = s }, enabled = !busy, label = { Text(s) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("« ajoute du lait bio »") },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 3,
                    enabled = model.available && !busy,
                )
                Spacer(Modifier.size(8.dp))
                if (busy) FilledIconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, "Arrêter") }
                else FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = model.available && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                }
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
                            if (p.organic) { BioBadge(); Spacer(Modifier.size(6.dp)) }
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            listOfNotNull(p.brand, p.unitPrice).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (editable && (line.candidates.size > 1 || line.remembered)) {
                            TextButton(onClick = onAlternatives, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp))
                                Text(
                                    if (line.remembered) " Ton choix habituel · autres choix" else " ${line.candidates.size - 1} autre(s) choix",
                                    style = MaterialTheme.typography.labelMedium,
                                )
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

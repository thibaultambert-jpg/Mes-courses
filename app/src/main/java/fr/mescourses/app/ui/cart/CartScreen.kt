@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.cart

import android.webkit.WebSettings
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Search
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
import fr.mescourses.app.retail.SessionState
import fr.mescourses.app.ui.retail.RetailerChips
import fr.mescourses.app.ui.retail.RetailerDot
import fr.mescourses.core.retail.Retailer
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

private fun euros(v: Double) = String.format(java.util.Locale.FRANCE, "%.2f €", v)

@Composable
fun CartScreen(
    onOpenSettings: () -> Unit,
    onLogin: (Retailer) -> Unit,
    onOpenSite: (Retailer, String) -> Unit,
    onChooseStore: (Retailer) -> Unit,
    onCompare: () -> Unit,
) {
    val vm = containerViewModel { CartViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val retailer by vm.retailer.collectAsStateWithLifecycle()
    val site = vm.site(retailer)
    val who = retailer.label
    fun open(url: String) = onOpenSite(retailer, url)
    var alternativesFor by remember { mutableStateOf<Int?>(null) }
    val productSheet by vm.productSheet.collectAsStateWithLifecycle()
    var chatOpen by remember { mutableStateOf(false) }
    val stepIndex = when (state.phase) {
        Phase.IDLE, Phase.RECHERCHE -> 0
        Phase.VALIDATION -> 1
        Phase.ENVOI, Phase.TERMINE -> 2
    }
    val selected = state.lines.filter { it.included && it.product != null && it.status != LineStatus.AJOUTE }
    val total = state.lines.filter { it.included && it.product != null }.sumOf { (it.product?.price ?: 0.0) * it.quantity }

    ScreenScaffold(
        title = "Panier $who",
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
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { open(site.webCart()) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Finaliser sur $who")
                        }
                        RetailerAppButton(retailer)
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
                // Enseigne du panier : la préférée par défaut ; en changer relance la recherche des produits.
                val busy = state.phase == Phase.RECHERCHE || state.phase == Phase.ENVOI
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RetailerChips(selected = retailer, preferred = settings.preferredRetailer, enabled = !busy, onSelect = vm::switchTo)
                    if (state.phase == Phase.IDLE || state.phase == Phase.VALIDATION) {
                        TextButton(onClick = { vm.prepareCompare(); onCompare() }) { Text("⚖️ Comparer les prix Carrefour · Leclerc · Auchan") }
                    }
                }
            }
            item {
                AccountCard(retailer, session, settings.storeName(retailer), onLogin = { onLogin(retailer) }, onChooseStore = { onChooseStore(retailer) })
            }
            if (session != SessionState.CONNECTE) {
                if (session == SessionState.VERIFICATION) item {
                    SectionCard {
                        Text("$who demande une vérification anti-robot.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { open(site.parking()) }) { Text("Ouvrir ${retailer.site}") }
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
                            "Je propose un produit par article de ta liste (frais et bio d'abord quand le drive en a, et tes choix précédents en priorité). " +
                                "Tu vérifies, puis j'ajoute tout au panier. La commande et le paiement se font ensuite sur ${retailer.site}.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.prepare() }) { Text("Depuis ma liste") }
                            OutlinedButton(onClick = { chatOpen = true }) { Text("💬 Avec l'assistant") }
                            TextButton(onClick = { vm.loadInfo() }) { Text("Voir mon panier") }
                        }
                        // Liste vide ou rien à préparer : accès direct au panier du site.
                        TextButton(onClick = { open(site.webCart()) }) { Text("Ouvrir mon panier sur ${retailer.site} ↗") }
                        RetailerAppButton(retailer)
                    }
                }
                Phase.TERMINE -> item {
                    SectionCard {
                        when {
                            state.noDrive && retailer == Retailer.CARREFOUR -> {
                                Text("🏪 Drive pas encore reconnu", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Carrefour ne m'a pas encore donné l'identifiant de ton drive : il apparaît dans ton panier dès qu'il contient " +
                                        "un article, ou dans les offres d'une recherche. Ajoute un premier article sur le site, puis réessaie.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { open(CarrefourRoutes.ORIGIN + CarrefourRoutes.search("lait")) }) { Text("Ajouter sur le site") }
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
                                Text("${state.added} produit(s) ajoutés. Choisis ton créneau et paie sur ${retailer.site}.")
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
                        onProduct = vm::openProduct,
                    )
                }
            }
            // Articles du panier que le drive ne pourra pas servir : remplaçants proposés.
            if (state.toReplace.isNotEmpty()) item {
                ReplacementsCard(state.toReplace, onReplace = vm::replace, onProduct = vm::openProduct)
            }
            if (state.loadingInfo) item { Busy("Lecture du panier…") }
            state.cart?.let { cart -> item { CartSummary(retailer, cart, state.slots, onOpenWebCart = { open(site.webCart()) }) } }
        }
    }

    alternativesFor?.let { index ->
        val line = state.lines.getOrNull(index)
        if (line != null) {
            ChoiceSheet(
                line = line,
                onDismiss = { alternativesFor = null },
                onPick = { i ->
                    vm.updateLine(index) { it.copy(selected = i, status = LineStatus.PROPOSE, included = true) }
                    alternativesFor = null
                },
                onSearch = { q -> vm.searchAgain(index, q) },
                onWiden = { vm.widen(index) },
                onProduct = vm::openProduct,
            )
        }
    }

    productSheet?.let { sheet -> ProductDetailSheet(sheet, onDismiss = vm::closeProduct) }

    if (chatOpen) ShoppingChatSheet(onDismiss = { chatOpen = false })
}

/** Site d'où la photo est censée être demandée : le serveur d'images de l'enseigne peut exiger ce Referer. */
private fun imageReferer(url: String): String = when {
    "leclercdrive" in url -> fr.mescourses.core.leclerc.LeclercRoutes.HOME
    "auchan" in url -> fr.mescourses.core.auchan.AuchanRoutes.HOME
    else -> CarrefourRoutes.ORIGIN + "/"
}

/**
 * Requête d'image comme depuis carrefour.fr (Referer + User-Agent de Chrome) : le serveur d'images peut
 * refuser les requêtes qui ne viennent pas d'un navigateur sur le site.
 */
@Composable
private fun carrefourImage(url: String): ImageRequest {
    val context = LocalContext.current
    val ua = remember { fr.mescourses.core.recipes.RecipeSites.chromeUserAgent(WebSettings.getDefaultUserAgent(context)) }
    return remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .addHeader("Referer", imageReferer(url))
            .addHeader("User-Agent", ua)
            .crossfade(true)
            .build()
    }
}

/** Vignette d'un produit (photo de l'enseigne, sinon emoji). */
@Composable
private fun ProductThumb(p: Product, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val imageUrl = p.imageUrl
        var failed by remember(imageUrl) { mutableStateOf(false) }
        if (imageUrl != null && !failed) {
            AsyncImage(
                model = carrefourImage(imageUrl),
                contentDescription = p.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        } else {
            Text("🛒", fontSize = (size / 2.5).sp)
        }
    }
}

/** Fiche produit : grande photo, prix, Nutri-Score, description, composition, allergènes. */
@Composable
private fun ProductDetailSheet(sheet: ProductSheet, onDismiss: () -> Unit) {
    val p = when (sheet) { is ProductSheet.Loading -> sheet.product; is ProductSheet.Loaded -> sheet.product }
    val detail = (sheet as? ProductSheet.Loaded)?.detail
    val image = p.imageUrl ?: detail?.product?.imageUrl
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (image != null) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    AsyncImage(model = carrefourImage(image), contentDescription = p.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.organic) { BioBadge(); Spacer(Modifier.size(6.dp)) }
                Text(p.name, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                listOfNotNull(p.brand ?: detail?.product?.brand, p.packaging).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (p.price ?: detail?.product?.price)?.let { StatChip(euros(it), emphasized = true) }
                p.unitPrice?.let { StatChip(it) }
                (p.nutriscore ?: detail?.product?.nutriscore)?.let { StatChip("Nutri-Score $it") }
            }
            p.promo?.let { Text("🏷 $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            if (!p.available || detail?.product?.available == false) {
                Text("⚠ Indisponible dans ton drive pour le moment", color = MaterialTheme.colorScheme.error)
            }
            if (sheet is ProductSheet.Loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                val description = detail?.description ?: p.description
                description?.let { Section("Description", it) }
                detail?.ingredients?.let { Section("Ingrédients", it) }
                detail?.allergens?.let { Section("Allergènes", it) }
                if (description == null && detail?.ingredients == null && detail?.allergens == null) {
                    Text("Pas de description fournie par l'enseigne pour ce produit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim(), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Articles que le drive ne pourra pas servir, avec jusqu'à 3 remplaçants disponibles. */
@Composable
private fun ReplacementsCard(items: List<Replacement>, onReplace: (Replacement, Product) -> Unit, onProduct: (Product) -> Unit) {
    SectionCard {
        Text("⚠ À remplacer", style = MaterialTheme.typography.titleMedium)
        Text(
            "Le drive indique que ces articles de ton panier ne seront pas disponibles. Choisis un remplaçant :",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { r ->
            Text((if (r.done) "✅ " else "") + r.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (r.done) {
                Text("Remplacé.", style = MaterialTheme.typography.bodySmall)
            } else if (r.alternatives.isEmpty()) {
                Text("Aucun remplaçant disponible trouvé : retire-le ou choisis sur carrefour.fr.", style = MaterialTheme.typography.bodySmall)
            } else {
                r.alternatives.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onProduct(p) }.padding(vertical = 4.dp)) {
                        ProductThumb(p, 44)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(p.brand, p.price?.let { euros(it) }, p.unitPrice).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onReplace(r, p) }) { Text("Remplacer") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(retailer: Retailer, session: SessionState, storeName: String, onLogin: () -> Unit, onChooseStore: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RetailerDot(retailer)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(retailer.accountTitle, style = MaterialTheme.typography.titleMedium)
                Text(storeName.ifBlank { retailer.noStoreText() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatChip(session.label, emphasized = session != SessionState.CONNECTE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session == SessionState.CONNEXION_REQUISE || session == SessionState.INCONNU || session == SessionState.NON_VERIFIE) Button(onClick = onLogin) { Text("Se connecter") }
            OutlinedButton(onClick = onChooseStore) { Text(retailer.chooseStoreText(storeName.isNotBlank())) }
        }
    }
}

@Composable
private fun CartSummary(retailer: Retailer, cart: fr.mescourses.core.carrefour.Cart, slots: List<fr.mescourses.core.carrefour.TimeSlot>?, onOpenWebCart: () -> Unit) {
    SectionCard {
        Text("Mon panier ${retailer.label}", style = MaterialTheme.typography.titleMedium)
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
        if (slots != null && slots.isEmpty() && retailer != Retailer.CARREFOUR) {
            Text("Les créneaux se choisissent sur ${retailer.site}.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onOpenWebCart) { Text("🛒 Voir mon panier sur ${retailer.site}") }
        RetailerAppButton(retailer)
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

    // Le clavier est géré ici seulement (pas aussi par la feuille), et la liste rétrécit pour laisser voir le champ.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), contentWindowInsets = { WindowInsets(0) }) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text("Courses avec l'assistant", style = MaterialTheme.typography.titleLarge)
            Text(
                if (model.available) "Demande en français ; tu confirmes chaque ajout." else "Installe l'assistant dans les Réglages pour utiliser ce mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                model == ModelStatus.Loading -> ModelLoadingCard(Modifier.padding(top = 8.dp))
                busy -> ChefCard("Le chef réfléchit…", "Il cherche les produits au drive.", Modifier.padding(top = 8.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun CartLineCard(
    line: CartLine,
    editable: Boolean,
    onChange: ((CartLine) -> CartLine) -> Unit,
    onAlternatives: () -> Unit,
    onProduct: (Product) -> Unit,
) {
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
            product?.let { p ->
                Box(Modifier.padding(end = 10.dp).clickable { onProduct(p) }) { ProductThumb(p, 52) }
            }
            Column(Modifier.weight(1f)) {
                Text(ShoppingListMerger.format(line.item), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (line.status) {
                    LineStatus.A_CHERCHER -> Text("Recherche…", style = MaterialTheme.typography.bodyMedium)
                    LineStatus.INTROUVABLE -> {
                        Text("Aucun produit trouvé", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        line.note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (editable) {
                            TextButton(onClick = onAlternatives, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Filled.Search, null, Modifier.size(16.dp))
                                Text(" Modifier la recherche" + if (line.candidates.isNotEmpty()) " · voir les résultats" else "", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
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
                        line.note?.let { Text("⚠ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
                        if (editable) {
                            TextButton(onClick = onAlternatives, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp))
                                Text(
                                    if (line.remembered) " Ton choix habituel · autres choix"
                                    else if (line.candidates.size > 1) " ${line.candidates.size - 1} autre(s) choix · modifier la recherche"
                                    else " Modifier la recherche",
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


/**
 * Choix du produit d'une ligne : recherche modifiable (relancée pour cette seule ligne et mémorisée),
 * résultats pertinents d'abord, « sans rapport » à part, et tous les produits reçus sur demande.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    line: CartLine,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onWiden: () -> Unit,
    onProduct: (Product) -> Unit,
) {
    var query by remember(line.query) { mutableStateOf(line.query ?: fr.mescourses.core.cart.CartPlanner.searchQuery(line.item)) }
    var showAll by remember(line.query) { mutableStateOf(false) }
    val relevant = line.relevantCount.coerceIn(0, line.candidates.size)
    val shownRelevant = if (showAll) relevant else minOf(relevant, 8)
    val searching = line.status == LineStatus.A_CHERCHER
    val canWiden = fr.mescourses.core.cart.CartPlanner.widen(line.query ?: query) != null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text("Choisir pour « ${ShoppingListMerger.format(line.item)} »", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Recherche au drive") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { onSearch(query) }, enabled = !searching && query.isNotBlank()) { Text("Relancer") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (canWiden) TextButton(onClick = onWiden, enabled = !searching) { Text("Élargir la recherche") }
                        Text(
                            "La recherche modifiée est retenue pour cet ingrédient.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    line.note?.let { Text("⚠ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
                    if (!searching && line.candidates.isEmpty()) Text("Aucun produit reçu pour « ${line.query ?: query} ».", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(shownRelevant) { i -> ChoiceRow(line, i, onPick, onProduct) }
            if (shownRelevant < relevant) item {
                TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Voir tous les résultats (${relevant - shownRelevant} de plus)")
                }
            }
            if (line.candidates.size > relevant && (showAll || relevant == 0)) {
                item {
                    Text(
                        "Autres résultats (sans rapport avec « ${line.query ?: query} »)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(line.candidates.size - relevant) { j -> ChoiceRow(line, relevant + j, onPick, onProduct) }
            } else if (line.candidates.size > relevant) item {
                TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Voir aussi ${line.candidates.size - relevant} résultat(s) sans rapport")
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun ChoiceRow(line: CartLine, i: Int, onPick: (Int) -> Unit, onProduct: (Product) -> Unit) {
    val p = line.candidates[i]
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i == line.selected && line.status == LineStatus.PROPOSE) { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(4.dp)) }
                if (p.organic) { BioBadge(); Spacer(Modifier.size(4.dp)) }
                Text(
                    p.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            Column {
                if (!p.available) Text("⛔ Indisponible au drive", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                Text(listOfNotNull(p.brand, p.packaging, p.unitPrice, p.nutriscore?.let { "Nutri-Score $it" }, p.promo).joinToString(" · "))
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                p.price?.let { Text(euros(it), style = MaterialTheme.typography.titleSmall) }
                TextButton(onClick = { onProduct(p) }, contentPadding = PaddingValues(0.dp)) { Text("Détails", style = MaterialTheme.typography.labelMedium) }
            }
        },
        leadingContent = { ProductThumb(p, 56) },
        modifier = Modifier.clickable(enabled = p.available) { onPick(i) },
    )
}


/** Lance l'application officielle de l'enseigne si elle est installée (null sinon). */
private fun retailerAppIntent(context: android.content.Context, retailer: Retailer): android.content.Intent? =
    runCatching { context.packageManager.getLaunchIntentForPackage(retailer.appPackage) }.getOrNull()

/** « Ouvrir l'app E.Leclerc » : affiché seulement quand l'app de l'enseigne est installée sur le téléphone. */
@Composable
private fun RetailerAppButton(retailer: Retailer) {
    val context = LocalContext.current
    val intent = remember(retailer) { retailerAppIntent(context, retailer) } ?: return
    TextButton(onClick = {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }) { Text("Ouvrir l'app ${retailer.label} ↗") }
}

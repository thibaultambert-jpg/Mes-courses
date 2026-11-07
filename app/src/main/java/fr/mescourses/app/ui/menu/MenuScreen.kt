@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package fr.mescourses.app.ui.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.BalanceDots
import fr.mescourses.app.ui.components.BottomActionBar
import fr.mescourses.app.ui.components.DishArt
import fr.mescourses.app.ui.components.EmptyState
import fr.mescourses.app.ui.components.IngredientChip
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.app.ui.components.MiniStepper
import fr.mescourses.app.ui.components.PillStepper
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.SectionHeader
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.app.ui.components.StepIndicator
import fr.mescourses.app.ui.components.balanceHint
import fr.mescourses.core.menu.IngredientOverlap
import fr.mescourses.core.menu.PlateBalance
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.shopping.ShoppingListMerger

private val CHAT_SUGGESTIONS = listOf(
    "Ajoute 2 adultes au plat 1",
    "Plus rapide à préparer",
    "Plus végétarien",
    "Remplace le poisson",
)

@Composable
fun MenuScreen(onOpenSettings: () -> Unit, onOpenRecipe: (Long) -> Unit, onOpenShopping: () -> Unit) {
    val vm = containerViewModel { MenuViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val dishes by vm.dishes.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val overlap by vm.overlap.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var chatOpen by remember { mutableStateOf(false) }
    val ready = state.model.available

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    ScreenScaffold(
        title = "Menu de la semaine",
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
        bottomBar = {
            if (step == MenuStep.PROPOSITION && dishes.isNotEmpty() && state.validation == null) {
                BottomActionBar {
                    OutlinedButton(onClick = { chatOpen = true }, enabled = ready) {
                        Icon(Icons.Filled.ChatBubbleOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Ajuster")
                    }
                    Button(onClick = vm::validate, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Valider le menu")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StepIndicator(
                steps = MenuStep.entries.map { it.label },
                current = step.ordinal,
                onStep = { vm.goTo(MenuStep.entries[it]) },
                enabled = { i ->
                    when (MenuStep.entries[i]) {
                        MenuStep.REGLAGES -> !state.busy
                        MenuStep.PROPOSITION -> dishes.isNotEmpty()
                        MenuStep.SEMAINE -> week.isNotEmpty() && !state.busy
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            AnimatedContent(
                targetState = if (state.validation != null) null else step,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "step",
                modifier = Modifier.weight(1f),
            ) { target ->
                when (target) {
                    null -> state.validation?.let { ValidationProgress(it, dishes) }
                    MenuStep.REGLAGES -> SettingsStep(vm, state, onOpenSettings)
                    MenuStep.PROPOSITION -> ProposalStep(
                        dishes = dishes, favorites = favorites, overlap = overlap, state = state, ready = ready,
                        onPortions = vm::changePortions, onAnother = vm::anotherIdea, onRemove = vm::removeDish,
                        onImprove = vm::improveSharing, onClear = vm::clearDraft,
                    )
                    MenuStep.SEMAINE -> WeekStep(
                        week = week, onToggle = vm::toggleCooked, onOpenRecipe = onOpenRecipe,
                        onOpenShopping = onOpenShopping, onNewMenu = vm::newMenu, onClear = vm::clearWeek,
                    )
                }
            }
        }
    }

    if (chatOpen) {
        ChatSheet(
            chat = state.chat, busy = state.busy, ready = ready,
            onSend = vm::send, onStop = vm::stop, onImprove = vm::improveSharing,
            onDismiss = { chatOpen = false },
        )
    }
}

// ---------- Étape 1 : réglages ----------

@Composable
private fun SettingsStep(vm: MenuViewModel, state: MenuUiState, onOpenSettings: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().imePadding(),
    ) {
        item { ModelStatusCard(state.model, onOpenSettings) { vm.loadModel() } }
        item {
            Column {
                Text("Qu'est-ce qu'on mange cette semaine ?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Plats sains, équilibrés (protéine, légumes, féculent), bio et de saison.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard {
                PillStepper("🍽", "Repas", state.meals, vm::setMeals, enabled = !state.busy, min = 1, max = 21)
                PillStepper("🧑", "Adultes par repas", state.adults, vm::setAdults, enabled = !state.busy, max = 20)
                PillStepper("🧒", "Enfants par repas", state.children, vm::setChildren, enabled = !state.busy, max = 20)
            }
        }
        item {
            SectionCard {
                Text("Envies de la semaine", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_WISHES.forEach { w ->
                        FilterChip(
                            selected = state.wishes.split(',').any { it.trim().equals(w, true) },
                            onClick = { vm.toggleWish(w) },
                            label = { Text(w) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.wishes,
                    onValueChange = vm::setWishes,
                    placeholder = { Text("Autre chose ? ex. « une raclette samedi »") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3,
                )
            }
        }
        item {
            InfoBanner(
                "🍂",
                "De saison : " + (vm.season.vegetables.take(8) + vm.season.fruits.take(4)).joinToString(", ") + "…",
            )
        }
        item {
            Button(
                onClick = vm::propose,
                enabled = state.model.available && !state.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.size(8.dp))
                Text("Proposer un menu de ${state.meals} plats", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ModelStatusCard(model: ModelStatus, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    when (model) {
        is ModelStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatChip("🤖 ${model.modelName} · ${model.backend}")
        }
        is ModelStatus.Sleeping -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatChip("🤖 ${model.modelName} · en veille")
        }
        ModelStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Réveil de l'assistant…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        ModelStatus.Absent -> SectionCard {
            Text("👋 Bienvenue !", style = MaterialTheme.typography.titleLarge)
            Text("Pour composer tes menus, l'app utilise un assistant qui tourne entièrement sur ton téléphone. Il faut le télécharger une fois (2 à 4 Go, en Wi-Fi).")
            Button(onClick = onOpenSettings) { Text("Installer l'assistant") }
        }
        is ModelStatus.Failed -> SectionCard {
            Text("L'assistant n'a pas pu démarrer", style = MaterialTheme.typography.titleMedium)
            Text(model.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Réessayer") }
        }
    }
}

// ---------- Étape 2 : proposition ----------

@Composable
private fun ProposalStep(
    dishes: List<MenuDish>,
    favorites: Set<Long>,
    overlap: IngredientOverlap.Report,
    state: MenuUiState,
    ready: Boolean,
    onPortions: (Int, Int, Int) -> Unit,
    onAnother: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onImprove: () -> Unit,
    onClear: () -> Unit,
) {
    val sharedNames = remember(overlap) { overlap.shared.map { ShoppingListMerger.key(it.name) }.toSet() }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (dishes.isEmpty() && state.busy) {
            item { ThinkingCard(state.chat.lastOrNull { !it.fromUser && !it.tool }?.text) }
            return@LazyColumn
        }
        item {
            val balanced = dishes.count { PlateBalance.missing(dishBalanceTexts(it)).isEmpty() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                StatChip("🍽 ${dishes.size} plats", emphasized = true)
                StatChip("♻ ${overlap.shared.size} ingrédients partagés")
                StatChip("⚖ $balanced/${dishes.size} équilibrés")
            }
        }
        itemsIndexed(dishes, key = { i, d -> "$i-${d.title}" }) { i, dish ->
            DishCard(
                number = i + 1,
                dish = dish,
                favorite = dish.recipeId != null && dish.recipeId in favorites,
                sharedNames = sharedNames,
                enabled = !state.busy,
                canAskLlm = ready,
                onPortions = { a, c -> onPortions(i, a, c) },
                onAnother = { onAnother(i) },
                onRemove = { onRemove(i) },
                modifier = Modifier.animateItem(),
            )
        }
        if (overlap.hasData) item { SharingCard(overlap, enabled = ready && !state.busy, onImprove = onImprove) }
        if (state.busy) item { ThinkingCard(null) }
        item {
            TextButton(onClick = onClear, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Tout effacer et recommencer") }
        }
    }
}

private fun dishBalanceTexts(d: MenuDish) = listOf(d.title, d.description) + d.mainIngredients

@Composable
private fun ThinkingCard(lastText: String?) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
            Column(Modifier.padding(start = 14.dp)) {
                Text("Le chef réfléchit…", style = MaterialTheme.typography.titleSmall)
                Text(
                    lastText?.takeLast(140)?.trim() ?: "Saison, équilibre, ingrédients partagés : il compose ton menu.",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun DishCard(
    number: Int,
    dish: MenuDish,
    favorite: Boolean,
    sharedNames: Set<String>,
    enabled: Boolean,
    canAskLlm: Boolean,
    onPortions: (Int, Int) -> Unit,
    onAnother: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val present = PlateBalance.components(dishBalanceTexts(dish))
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            DishArt(dish.title, dish.mainIngredients, modifier = Modifier.fillMaxWidth().height(96.dp), emojiSize = 48)
            Text(
                "$number",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp, vertical = 2.dp),
            )
            if (favorite) {
                Icon(
                    Icons.Filled.Star, "Favori", tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(dish.title, style = MaterialTheme.typography.titleMedium)
                    if (dish.description.isNotBlank()) {
                        Text(dish.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onAnother, enabled = enabled && canAskLlm) { Icon(Icons.Filled.Autorenew, "Autre idée") }
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Filled.DeleteOutline, "Retirer") }
            }
            if (dish.mainIngredients.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dish.mainIngredients.forEach { IngredientChip(it, shared = ShoppingListMerger.key(it).let { k -> sharedNames.any { s -> s == k || k.startsWith(s) } }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStepper("🧑", dish.portions.adults, { onPortions(it - dish.portions.adults, 0) }, enabled, "Adultes")
                MiniStepper("🧒", dish.portions.children, { onPortions(0, it - dish.portions.children) }, enabled, "Enfants")
                Spacer(Modifier.weight(1f))
                BalanceDots(present)
            }
            balanceHint(present)?.let {
                Text("⚠ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SharingCard(report: IngredientOverlap.Report, enabled: Boolean, onImprove: () -> Unit) {
    SectionCard {
        SectionHeader("Ingrédients partagés", emoji = "♻")
        if (report.shared.isEmpty()) {
            Text("Aucun ingrédient ne sert à plusieurs plats pour l'instant.", style = MaterialTheme.typography.bodyMedium)
        }
        report.shared.take(8).forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                u.dishes.forEach { n ->
                    Text(
                        "$n",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (report.isolated.isNotEmpty()) {
            Text(
                "Pour un seul plat : " + report.isolated.take(10).joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.isolated.size > 1) {
            OutlinedButton(onClick = onImprove, enabled = enabled) { Text("♻ Mieux mutualiser") }
        }
    }
}

@Composable
private fun ValidationProgress(v: Validation, dishes: List<MenuDish>) {
    val progress by animateFloatAsState(v.done.toFloat() / v.total.coerceAtLeast(1), label = "progress")
    val current = dishes.firstOrNull { it.title == v.current }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DishArt(
            v.current.ifBlank { "Menu" }, current?.mainIngredients.orEmpty(),
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(40.dp)), emojiSize = 72,
        )
        Text(
            if (v.current.isNotBlank()) "J'écris la recette" else "Je prépare ta liste…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        if (v.current.isNotBlank()) {
            Text(v.current, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(8.dp).clip(RoundedCornerShape(50)),
        )
        Text(
            "Recette ${(v.done + 1).coerceAtMost(v.total)} / ${v.total}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Les recettes sont écrites par l'assistant sur ton téléphone : compte environ une minute par plat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

// ---------- Étape 3 : semaine ----------

@Composable
private fun WeekStep(
    week: List<PlannedMeal>,
    onToggle: (PlannedMeal) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onOpenShopping: () -> Unit,
    onNewMenu: () -> Unit,
    onClear: () -> Unit,
) {
    if (week.isEmpty()) {
        EmptyState("🗓", "Pas encore de menu validé", "Prépare un menu pour le voir ici.", actionLabel = "Préparer un menu", onAction = onNewMenu)
        return
    }
    val cooked = week.count { it.cooked }
    val progress by animateFloatAsState(cooked.toFloat() / week.size, label = "week")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            SectionCard {
                Text("$cooked / ${week.size} plats cuisinés", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenShopping) { Text("🛒 Liste de courses") }
                    OutlinedButton(onClick = onNewMenu) { Text("Nouveau menu") }
                }
            }
        }
        weekItems(week, onToggle, onOpenRecipe)
        item { TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Effacer le menu de la semaine") } }
    }
}

private fun LazyListScope.weekItems(week: List<PlannedMeal>, onToggle: (PlannedMeal) -> Unit, onOpenRecipe: (Long) -> Unit) {
    items(week, key = { it.id }) { meal ->
        Card(
            onClick = { onOpenRecipe(meal.recipeId) },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            modifier = Modifier.fillMaxWidth().animateItem(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DishArt(meal.recipeTitle, modifier = Modifier.size(76.dp), emojiSize = 32)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        meal.recipeTitle,
                        style = MaterialTheme.typography.titleSmall,
                        textDecoration = if (meal.cooked) TextDecoration.LineThrough else null,
                        color = if (meal.cooked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(meal.portions.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = meal.cooked, onCheckedChange = { onToggle(meal) })
            }
        }
    }
}

// ---------- Feuille de discussion ----------

@Composable
private fun ChatSheet(
    chat: List<ChatLine>,
    busy: Boolean,
    ready: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onImprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp)) {
            Text("Ajuster le menu", style = MaterialTheme.typography.titleLarge)
            Text(
                "Demande en français : invités sur un plat, remplacer une recette, plus rapide…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(320.dp).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (chat.isEmpty()) item {
                    Text("💬 Pas encore de message.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                items(chat) { ChatBubble(it) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = onImprove, enabled = ready && !busy, label = { Text("♻ Mieux mutualiser") })
                CHAT_SUGGESTIONS.forEach { s ->
                    SuggestionChip(onClick = { input = s }, enabled = !busy, label = { Text(s) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("« ajoute 3 adultes et 2 enfants au plat 2 »") },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 3,
                    enabled = ready && !busy,
                )
                Spacer(Modifier.size(8.dp))
                if (busy) {
                    FilledIconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Arrêter") }
                } else {
                    FilledIconButton(onClick = { onSend(input); input = "" }, enabled = ready && input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    if (line.tool) {
        Text(
            line.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (line.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            line.text.trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (line.fromUser) 20.dp else 4.dp,
                        bottomEnd = if (line.fromUser) 4.dp else 20.dp,
                    ),
                )
                .background(if (line.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}


@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package fr.mescourses.app.ui.menu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import fr.mescourses.core.menu.Candidate
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import fr.mescourses.app.ui.common.appContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.ui.components.ChefCard
import fr.mescourses.app.ui.components.ModelLoadingCard
import fr.mescourses.app.work.Validation
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
import fr.mescourses.core.menu.CookingTime
import fr.mescourses.core.menu.DishKind
import fr.mescourses.core.menu.IngredientOverlap
import fr.mescourses.core.menu.MenuInsights
import fr.mescourses.core.menu.Novelty
import fr.mescourses.core.menu.ShoppingPreview
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.season.Seasonality
import fr.mescourses.core.menu.PlateBalance
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.shopping.ShoppingListMerger

private val CHAT_SUGGESTIONS = listOf(
    "Ajoute 2 adultes au plat 1",
    "Plus rapide à préparer",
    "Plus végétarien",
    "Cherche un curry de légumes",
)

@Composable
fun MenuScreen(onOpenSettings: () -> Unit, onOpenRecipe: (Long) -> Unit, onOpenShopping: () -> Unit, onOpenPreview: (String) -> Unit = {}) {
    val vm = containerViewModel { MenuViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val dishes by vm.dishes.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val pendingSteps by vm.pendingSteps.collectAsStateWithLifecycle()
    val stepsProgress by vm.stepsProgress.collectAsStateWithLifecycle()
    val recipeImages by vm.recipeImages.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val overlap by vm.overlap.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val preview by vm.shoppingPreview.collectAsStateWithLifecycle()
    // Demande de recette inventée : null = fermé ; -1 = plat en plus ; sinon l'index du plat à remplacer.
    var inventFor by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var chatOpen by remember { mutableStateOf(false) }
    val ready = state.model.available
    val askNotifications = rememberNotificationPermission()

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    ScreenScaffold(
        title = "Menu de la semaine",
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
        bottomBar = {
            // Le bouton reste sous le pouce, quel que soit l'endroit du formulaire.
            if (step == MenuStep.REGLAGES && state.validation == null) {
                BottomActionBar {
                    Button(
                        onClick = { askNotifications(); vm.propose() },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Composer mon menu de " + listOfNotNull(
                            state.starters.takeIf { it > 0 }?.let { "$it entrée${if (it > 1) "s" else ""}" },
                            "${state.meals} plat${if (state.meals > 1) "s" else ""}",
                            state.desserts.takeIf { it > 0 }?.let { "$it dessert${if (it > 1) "s" else ""}" },
                        ).joinToString(", "), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            if (step == MenuStep.PROPOSITION && dishes.isNotEmpty() && state.validation == null) {
                BottomActionBar {
                    OutlinedButton(onClick = { chatOpen = true }) {
                        Icon(Icons.Filled.ChatBubbleOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Ajuster")
                    }
                    Button(onClick = { askNotifications(); vm.validate() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
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
                    null -> state.validation?.let { ValidationProgress(it, dishes, state.model == ModelStatus.Loading) }
                    MenuStep.REGLAGES -> SettingsStep(vm, state, onOpenSettings)
                    MenuStep.PROPOSITION -> ProposalStep(
                        dishes = dishes, favorites = favorites, overlap = overlap, insights = insights, preview = preview,
                        state = state, ready = ready,
                        onPortions = vm::changePortions, onAnother = vm::anotherRecipe, onInvent = { inventFor = it ?: -1 },
                        onOpen = onOpenPreview, onOpenRecipe = onOpenRecipe, onRemove = vm::removeDish, onAdd = vm::openPicker,
                        onImprove = vm::improveSharing, onClear = vm::clearDraft,
                    )
                    MenuStep.SEMAINE -> WeekStep(
                        images = recipeImages, stepsProgress = stepsProgress, onStopGenerating = vm::stopGeneratingSteps, pendingSteps = pendingSteps, chefInstalled = state.model != ModelStatus.Absent, onGenerate = vm::generateMissingSteps,
                        week = week, onToggle = vm::toggleCooked, onOpenRecipe = onOpenRecipe,
                        onOpenShopping = onOpenShopping, onNewMenu = vm::newMenu, onClear = vm::clearWeek,
                    )
                }
            }
        }
    }

    inventFor?.let { target ->
        InventDialog(
            replacing = dishes.getOrNull(target)?.title,
            onDismiss = { inventFor = null },
            onCreate = { idea -> inventFor = null; vm.invent(target.takeIf { it >= 0 }, idea) },
        )
    }

    if (chatOpen) {
        ChatSheet(
            chat = state.chat, busy = state.busy, ready = ready, modelLoading = state.model == ModelStatus.Loading,
            onSend = vm::send, onStop = vm::stop, onImprove = vm::improveSharing,
            canUndo = state.canUndo, onUndo = { vm.undo() }, onAddDish = { chatOpen = false; vm.openPicker() },
            onDismiss = { chatOpen = false },
        )
    }

    state.picker?.let { recipes ->
        AddDishSheet(
            recipes = recipes, busy = state.pickerBusy,
            onSearch = vm::searchPicker, onPick = { vm.addCandidate(it) }, onOpen = onOpenPreview, onDismiss = vm::closePicker,
        )
    }
}

/**
 * Android 13+ : demande (une fois) le droit d'afficher la notification « le chef travaille », qui permet
 * de continuer quand on quitte l'app. Sans ce droit, le travail continue quand même, sans notification visible.
 */
@Composable
private fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ---------- Étape 1 : envies de la semaine ----------

/** Choix unique parmi quelques options, en pastilles. */
@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, enabled = enabled, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun SettingsStep(vm: MenuViewModel, state: MenuUiState, onOpenSettings: () -> Unit) {
    val enabled = !state.busy
    val welcomeCollapsed by vm.welcomeCollapsed.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().imePadding(),
    ) {
        item { ModelStatusCard(state.model, welcomeCollapsed, vm::setWelcomeCollapsed, onOpenSettings) { vm.loadModel() } }
        item {
            Column {
                Text("Qu'est-ce qu'on mange cette semaine ?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Dis-moi tes envies : je cherche de vraies recettes de saison sur les sites de cuisine et je compose un menu qui limite les courses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard {
                SectionHeader("Pour qui ?", emoji = "🍽")
                PillStepper("🥗", "Entrées", state.starters, vm::setStarters, enabled = enabled, min = 0, max = 21)
                PillStepper("🍽", "Plats", state.meals, vm::setMeals, enabled = enabled, min = 1, max = 21)
                PillStepper("🍰", "Desserts", state.desserts, vm::setDesserts, enabled = enabled, min = 0, max = 21)
                PillStepper("🧑", "Adultes par repas", state.adults, vm::setAdults, enabled = enabled, max = 20)
                PillStepper("🧒", "Enfants par repas", state.children, vm::setChildren, enabled = enabled, max = 20)
            }
        }
        item {
            SectionCard {
                SectionHeader("Temps en cuisine", emoji = "⏱")
                ChoiceRow(CookingTime.entries, state.time, { it.label }, enabled, vm::setTime)
                Text(
                    "Par repas, préparation et cuisson comprises : ${state.time.detail}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            val free = state.meals - state.vegetarian - state.fish - state.meat
            SectionCard {
                SectionHeader("Composition de la semaine", emoji = "🥗")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStepper("🥦 Végé", state.vegetarian, { vm.setQuota(DishKind.VEGE, it) }, enabled, "Repas végétariens")
                    MiniStepper("🐟 Poisson", state.fish, { vm.setQuota(DishKind.POISSON, it) }, enabled, "Repas de poisson")
                    MiniStepper("🍗 Viande", state.meat, { vm.setQuota(DishKind.VIANDE, it) }, enabled, "Repas de viande")
                }
                Text(
                    when {
                        free == state.meals -> "Rien d'imposé : je varie pour toi."
                        free > 0 -> "$free repas libre${if (free > 1) "s" else ""}."
                        else -> "Tous les repas sont répartis."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard {
                SectionHeader("À écouler", emoji = "🧊")
                Text(
                    "Ce qui attend dans le frigo : je cherche des recettes qui l'utilisent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.useUp.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.useUp.forEach { item ->
                            InputChip(
                                selected = false,
                                onClick = { vm.removeUseUp(item) },
                                enabled = enabled,
                                label = { Text(item) },
                                trailingIcon = { Icon(Icons.Filled.Close, "Retirer $item", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                var input by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("ex. un demi chou, crème fraîche") },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.addUseUp(input); input = "" }, enabled = enabled && input.isNotBlank()) { Text("Ajouter") }
                }
            }
        }
        item {
            SectionCard {
                SectionHeader("Nouveautés", emoji = "✨")
                ChoiceRow(Novelty.entries, state.novelty, { it.label }, enabled, vm::setNovelty)
                Text(
                    when (state.novelty) {
                        Novelty.CLASSIQUES -> "Surtout tes favoris et tes plats déjà cuisinés, avec quelques découvertes."
                        Novelty.EQUILIBRE -> "Des découvertes des sites, et un ou deux plats de ton livre."
                        Novelty.DECOUVERTES -> "Uniquement de nouvelles recettes des sites."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard {
                SectionHeader("Une envie particulière ?", emoji = "💬")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_WISHES.forEach { w ->
                        FilterChip(
                            selected = state.wishes.split(',').any { it.trim().equals(w, true) },
                            onClick = { vm.toggleWish(w) },
                            enabled = enabled,
                            label = { Text(w) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.wishes,
                    onValueChange = vm::setWishes,
                    placeholder = { Text("ex. « une raclette samedi », séparées par des virgules") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    enabled = enabled,
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
    }
}

@Composable
private fun ModelStatusCard(
    model: ModelStatus,
    collapsed: Boolean,
    onCollapse: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    when (model) {
        is ModelStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatChip("🤖 ${model.modelName} · ${model.backend}")
        }
        is ModelStatus.Sleeping -> SectionCard {
            Text("🤖 Assistant installé", style = MaterialTheme.typography.titleMedium)
            Text("${model.modelName} est prêt à être activé sur ton téléphone (quelques secondes).", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRetry) { Text("Activer le chef") }
        }
        ModelStatus.Loading -> ModelLoadingCard()
        ModelStatus.Absent -> SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("👋 Bienvenue !", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onCollapse(!collapsed) }) { Text(if (collapsed) "Déplier" else "Replier") }
            }
            if (!collapsed) {
                Text("Le menu se compose déjà à partir des sites de recettes. Pour l'ajuster en discutant et faire créer des recettes, installe l'assistant qui tourne sur ton téléphone (2 à 4 Go, en Wi-Fi).")
                Button(onClick = onOpenSettings) { Text("Installer l'assistant") }
            }
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
    insights: MenuInsights,
    preview: ShoppingPreview?,
    state: MenuUiState,
    ready: Boolean,
    onPortions: (Int, Int, Int) -> Unit,
    onAnother: (Int) -> Unit,
    /** Recette inventée par le chef : à la place du plat donné, ou en plus (null). */
    onInvent: (Int?) -> Unit,
    onOpen: (String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onImprove: () -> Unit,
    onClear: () -> Unit,
) {
    val sharedNames = remember(overlap) { overlap.shared.map { ShoppingListMerger.key(it.name) }.toSet() }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
    // Toujours visible en haut, même quand on fait défiler les plats.
    ChefStatus(
        modelLoading = state.model == ModelStatus.Loading,
        busy = state.busy,
        lastText = state.chat.lastOrNull { !it.fromUser && !it.tool }?.text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        progress = state.progress,
    )
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.weight(1f),
    ) {
        if (dishes.isEmpty() && state.busy) return@LazyColumn
        item {
            val mains = dishes.filter { it.isMain }
            val balanced = mains.count { PlateBalance.missing(dishBalanceTexts(it)).isEmpty() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                StatChip("🍽 ${mains.size} plats", emphasized = true)
                if (dishes.size > mains.size) StatChip("🥗 ${dishes.count { it.course == fr.mescourses.core.catalog.Course.ENTREE }} entrées · 🍰 ${dishes.count { it.course == fr.mescourses.core.catalog.Course.DESSERT }} desserts")
                StatChip("🛒 ${preview?.items ?: insights.articles} articles")
                StatChip("🍂 ${insights.seasonal.size} de saison")
                StatChip("♻ ${overlap.shared.size} partagés")
                StatChip("⚖ $balanced/${mains.size} équilibrés")
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
                onInvent = { onInvent(i) },
                onOpen = onOpen,
                onOpenRecipe = onOpenRecipe,
                onRemove = { onRemove(i) },
                modifier = Modifier.animateItem(),
            )
        }
        item {
            Button(onClick = onAdd, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Ajouter un plat")
            }
        }
        item {
            OutlinedButton(onClick = { onInvent(null) }, enabled = ready && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Créer une recette avec le chef")
            }
        }
        if (overlap.hasData) item { SharingCard(overlap, insights, preview, enabled = ready && !state.busy, onImprove = onImprove) }
        item {
            TextButton(onClick = onClear, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Tout effacer et recommencer") }
        }
    }
    }
}

private fun dishBalanceTexts(d: MenuDish) = listOf(d.title, d.description) + d.mainIngredients

@Composable
private fun ThinkingCard(lastText: String?, modifier: Modifier = Modifier, progress: String? = null) = ChefCard(
    progress ?: "Le chef réfléchit…",
    lastText?.takeLast(140)?.trim()?.ifBlank { null } ?: "Saison, équilibre, ingrédients partagés : il compose ton menu.",
    modifier,
)

/** Carte épinglée en haut tant que le chef travaille : chargement du modèle, sinon réflexion. */
@Composable
private fun ChefStatus(modelLoading: Boolean, busy: Boolean, lastText: String?, modifier: Modifier = Modifier, progress: String? = null) {
    when {
        modelLoading -> ModelLoadingCard(modifier)
        busy -> ThinkingCard(lastText, modifier, progress)
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
    onInvent: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openable = dish.sourceUrl != null || dish.recipeId != null
    val container = appContainer()
    // Les personnes de la carte suivent dans l'écran détail de la recette.
    val open = {
        container.hintPortions(dish.recipeId, dish.sourceUrl, dish.portions)
        dish.sourceUrl?.let(onOpen) ?: dish.recipeId?.let(onOpenRecipe); Unit
    }
    val present = PlateBalance.components(dishBalanceTexts(dish))
    val main = dish.isMain
    val season = remember(dish) { Seasonality.report(listOf(dish.title) + dish.mainIngredients, java.time.LocalDate.now().month) }
    // D'où vient le plat : un site (avec sa durée), le livre, ou le chef.
    val origin = listOfNotNull(
        dish.course?.let { if (it == fr.mescourses.core.catalog.Course.DESSERT) "🍰 Dessert" else "🥗 Entrée" },
        dish.sourceUrl?.let { RecipeSites.forUrl(it)?.name ?: "Site de recettes" },
        if (dish.sourceUrl == null && dish.recipeId != null) "Mon livre" else null,
        if (dish.sourceUrl == null && dish.recipeId == null) "✨ Recette du chef" else null,
        dish.totalMinutes?.let { "$it min" },
    ).joinToString(" · ")
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            DishArt(
                dish.title, dish.mainIngredients, imageUrl = dish.imageUrl,
                modifier = Modifier.fillMaxWidth().height(if (dish.imageUrl != null) 132.dp else 96.dp)
                    .clickable(enabled = openable) { open() },
                emojiSize = 48,
            )
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
                Column(
                    Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                        .clickable(enabled = openable) { open() },
                ) {
                    Text(dish.title, style = MaterialTheme.typography.titleMedium)
                    if (origin.isNotEmpty()) {
                        Text(origin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (dish.description.isNotBlank()) {
                        Text(
                            dish.description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = enabled) { Icon(Icons.Filled.Autorenew, "Autre idée") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("🔎 Autre recette des sites") }, onClick = { menuOpen = false; onAnother() })
                        DropdownMenuItem(
                            text = { Text("✨ Le chef invente une recette") },
                            enabled = canAskLlm,
                            onClick = { menuOpen = false; onInvent() },
                        )
                    }
                }
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
                if (main) BalanceDots(present)
            }
            if (season.seasonal.isNotEmpty()) {
                Text("🍂 De saison : ${season.seasonal.joinToString(", ")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (season.outOfSeason.isNotEmpty()) {
                Text("⚠ Hors saison : ${season.outOfSeason.joinToString(", ")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            balanceHint(present)?.takeIf { main }?.let {
                Text("⚠ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SharingCard(report: IngredientOverlap.Report, insights: MenuInsights, preview: ShoppingPreview?, enabled: Boolean, onImprove: () -> Unit) {
    SectionCard {
        SectionHeader("Ta liste de courses", emoji = "🛒")
        Text(
            "${preview?.items ?: insights.articles} articles à acheter" +
                if (insights.seasonal.isNotEmpty()) ", dont ${insights.seasonal.size} produits de saison (${insights.seasonal.joinToString(", ")})." else ".",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (insights.outOfSeason.isNotEmpty()) {
            Text(
                "Hors saison : " + insights.outOfSeason.joinToString(", ") { (n, name) -> "$name (plat $n)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!preview?.leftovers.isNullOrEmpty()) {
            Text(
                "Il restera : " + preview?.leftovers.orEmpty().joinToString(", ") + ". Une « autre recette » peut les utiliser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
private fun ValidationProgress(v: Validation, dishes: List<MenuDish>, modelLoading: Boolean) {
    val progress by animateFloatAsState(v.done.toFloat() / v.total.coerceAtLeast(1), label = "progress")
    val current = dishes.firstOrNull { it.title == v.current }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (modelLoading) ModelLoadingCard(Modifier.padding(bottom = 24.dp))
        DishArt(
            v.current.ifBlank { "Menu" }, current?.mainIngredients.orEmpty(),
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(40.dp)), emojiSize = 72,
        )
        Text(
            if (v.current.isNotBlank()) "Le chef prépare les ingrédients de" else "Le chef prépare ta liste…",
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
            "Plat ${(v.done + 1).coerceAtMost(v.total)} / ${v.total}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Les recettes des sites arrivent complètes dans ton livre. Pour une recette créée par le chef, les étapes s'écrivent quand tu l'ouvres. Tu peux quitter l'app, le chef continue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** Demande d'une recette créée par le chef (LLM), avec une idée facultative. */
@Composable
private fun InventDialog(replacing: String?, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var idea by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Créer une recette") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (replacing != null) "Le chef invente une recette à la place de « $replacing »." else "Le chef invente une recette et l'ajoute au menu.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = idea,
                    onValueChange = { idea = it },
                    placeholder = { Text("Une idée ? ex. « curry de légumes d'hiver »") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Laisse vide : il choisit un plat de saison qui réutilise les ingrédients du menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(idea) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------- Étape 3 : semaine ----------

@Composable
private fun WeekStep(
    images: Map<Long, String>,
    stepsProgress: Pair<Int, Int>?,
    onStopGenerating: () -> Unit,
    pendingSteps: Int,
    chefInstalled: Boolean,
    onGenerate: () -> Unit,
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
        if (stepsProgress != null) item {
            // Une fois lancé, le bouton laisse place à la progression de l'écriture.
            val (done, total) = stepsProgress
            val frac by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, label = "steps")
            SectionCard {
                Text("✍️ Le chef écrit les recettes : $done / $total", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
                TextButton(onClick = onStopGenerating) { Text("Arrêter") }
            }
        } else if (pendingSteps > 0) item {
            SectionCard {
                Text("✍️ $pendingSteps recette(s) sans étapes", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Le chef les écrit à partir des ingrédients, une par une, uniquement quand tu le demandes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGenerate, enabled = chefInstalled) { Text("Générer les données manquantes avec le chef") }
                if (!chefInstalled) Text("Installe l'assistant dans les Réglages pour l'utiliser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        weekItems(week, images, onToggle, onOpenRecipe)
        item { TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Effacer le menu de la semaine") } }
    }
}

private fun LazyListScope.weekItems(week: List<PlannedMeal>, images: Map<Long, String>, onToggle: (PlannedMeal) -> Unit, onOpenRecipe: (Long) -> Unit) {
    items(week, key = { it.id }) { meal ->
        val image = images[meal.recipeId]
        val container = appContainer()
        Card(
            onClick = { container.hintPortions(meal.recipeId, null, meal.portions); onOpenRecipe(meal.recipeId) },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (meal.cooked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            // Plat déjà cuisiné : la carte est grisée.
            modifier = Modifier.fillMaxWidth().animateItem().alpha(if (meal.cooked) 0.5f else 1f),
        ) {
            // Photo de la recette en grand (ou illustration quand le site n'en donne pas).
            DishArt(
                meal.recipeTitle, imageUrl = image,
                modifier = Modifier.fillMaxWidth().height(if (image != null) 150.dp else 96.dp),
                emojiSize = 48,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
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
    modelLoading: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onImprove: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onAddDish: () -> Unit,
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
                "Une demande à la fois : invités sur un plat, ajouter, retirer ou remplacer un plat…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChefStatus(
                modelLoading = modelLoading, busy = busy,
                lastText = null, // la réponse s'écrit déjà dans les bulles
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyColumn(
                state = listState,
                // La liste rétrécit quand le clavier s'ouvre : le champ de saisie reste visible au-dessus de lui.
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 72.dp, max = 320.dp).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (chat.isEmpty()) item {
                    Text("💬 Pas encore de message.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                items(chat) { ChatBubble(it) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUndo) SuggestionChip(onClick = onUndo, enabled = !busy, label = { Text("↩ Annuler la retouche") })
                SuggestionChip(onClick = onAddDish, enabled = !busy, label = { Text("＋ Ajouter un plat") })
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
                    enabled = !busy,
                )
                Spacer(Modifier.size(8.dp))
                if (busy) {
                    FilledIconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Arrêter") }
                } else {
                    FilledIconButton(onClick = { onSend(input); input = "" }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                    }
                }
            }
        }
    }
}

/** « Ajouter un plat » : les recettes disponibles pour ce menu, et une recherche dans le catalogue ou sur les sites. */
@Composable
private fun AddDishSheet(
    recipes: List<Candidate>,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onPick: (Candidate) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp)) {
            Text("Ajouter un plat", style = MaterialTheme.typography.titleLarge)
            Text(
                "Recettes disponibles pour ce menu. Touche une recette pour l'ajouter, ou cherche autre chose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Un plat, un ingrédient…") },
                    shape = RoundedCornerShape(28.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                )
                Spacer(Modifier.size(8.dp))
                FilledIconButton(onClick = { onSearch(query) }, enabled = !busy) { Icon(Icons.Filled.Search, "Chercher") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 72.dp, max = 460.dp).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (recipes.isEmpty() && !busy) item {
                    Text("Aucune recette disponible : lance une recherche ci-dessus.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                items(recipes, key = { it.recipe.sourceUrl ?: it.recipe.title }) { c ->
                    Card(onClick = { onPick(c) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.recipe.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        c.kind.label, c.minutes?.let { "$it min" }, c.siteName ?: if (c.fromCookbook) "Mon livre" else null,
                                        c.seasonal.takeIf { it.isNotEmpty() }?.let { "🍂 " + it.take(2).joinToString(", ") },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    c.mainIngredients.take(5).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            c.recipe.sourceUrl?.let { url -> TextButton(onClick = { onOpen(url) }) { Text("Voir") } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
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


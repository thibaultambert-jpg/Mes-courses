@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package fr.mescourses.app.ui.cookbook

import fr.mescourses.app.ui.discover.DiscoverViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.ui.components.ChefCard
import fr.mescourses.app.ui.components.ModelLoadingCard
import fr.mescourses.core.menu.MenuValidator
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.BalanceDots
import fr.mescourses.app.ui.components.BottomActionBar
import fr.mescourses.app.ui.components.DishArt
import fr.mescourses.app.ui.components.EmptyState
import fr.mescourses.app.ui.components.PillStepper
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.SectionHeader
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.app.ui.discover.AddByLinkDialog
import fr.mescourses.app.ui.discover.DiscoverContent
import fr.mescourses.app.ui.components.balanceHint
import fr.mescourses.core.menu.PlateBalance
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.shopping.ShoppingListMerger

private val RecipeStatus.emoji get() = when (this) {
    RecipeStatus.HISTORIQUE -> "🕘"
    RecipeStatus.FAVORI -> "⭐"
    RecipeStatus.BANNI -> "🚫"
}

@Composable
fun CookbookScreen(
    importUrl: String?,
    onImportConsumed: () -> Unit,
    onImported: (Long) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onNewRecipe: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPreview: (String) -> Unit,
    onBrowse: (String) -> Unit,
) {
    val vm = containerViewModel { CookbookViewModel(it) }
    val discover by vm.discover.collectAsStateWithLifecycle()
    // Même instance que celle de l'onglet Découvrir (même écran, même clé).
    val discoverVm = containerViewModel { DiscoverViewModel(it) }
    val discoverState by discoverVm.state.collectAsStateWithLifecycle()
    val discoverPopular by discoverVm.popularMode.collectAsStateWithLifecycle()
    // Retour : résultats de Découvrir → accueil de Découvrir (1er onglet) → onglet Menu. Depuis une liste du livre,
    // on revient d'abord à Découvrir.
    BackHandler(enabled = !discover || discoverState != DiscoverViewModel.State.Idle || discoverPopular) {
        if (!discover) vm.discover.value = true else discoverVm.back()
    }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    // Un lien partagé n'est importé qu'une fois, même si l'écran est recréé (retour, rotation).
    var importedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(importUrl) {
        if (importUrl != null && importedUrl != importUrl) {
            importedUrl = importUrl
            onImportConsumed()
            vm.import(importUrl) { onImported(it) }
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    ScreenScaffold(
        title = "Mes recettes",
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Ajouter") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Depuis un lien") },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = { addMenu = false; showImport = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Chercher sur les sites de recettes") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        onClick = { addMenu = false; vm.discover.value = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Écrire une recette") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { addMenu = false; onNewRecipe() },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = discover,
                    onClick = { vm.discover.value = true },
                    label = { Text("🔎 Découvrir", maxLines = 1) },
                )
                CookbookViewModel.TAB_ORDER.forEach { s ->
                    FilterChip(
                        selected = !discover && tab == s,
                        onClick = { vm.tab.value = s; vm.discover.value = false },
                        label = { Text("${s.emoji} ${if (s == RecipeStatus.BANNI) "Exclus" else s.label} ${counts[s] ?: 0}", maxLines = 1) },
                    )
                }
            }
            if (discover) {
                DiscoverContent(snackbar = snackbar, onOpenPreview = onOpenPreview, onBrowse = onBrowse)
            } else {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Chercher une recette") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (importing) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Import de la recette…", Modifier.padding(start = 8.dp))
            }
            if (recipes.isEmpty()) {
                when (tab) {
                    RecipeStatus.HISTORIQUE -> EmptyState("🕘", "Ton historique est vide", "Les plats des menus que tu valides arrivent ici.")
                    RecipeStatus.FAVORI -> EmptyState(
                        "⭐", "Aucun favori",
                        "Cherche-en dans « Découvrir », partage une recette depuis ton navigateur, colle un lien, ou écris-la. L'assistant pourra te la reproposer.",
                        actionLabel = "Découvrir des recettes", onAction = { vm.discover.value = true },
                    )
                    RecipeStatus.BANNI -> EmptyState("🚫", "Aucun plat exclu", "Les plats déplacés ici ne te seront plus jamais proposés.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tab == RecipeStatus.BANNI) {
                        item(span = { GridItemSpan(2) }) {
                            Text(
                                "L'assistant ne proposera jamais ces plats.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(recipes, key = { it.id }) { r ->
                        RecipeCard(r, onClick = { onOpenRecipe(r.id) }, onMove = { vm.move(r, it) }, modifier = Modifier.animateItem())
                    }
                }
            }
            }
        }
    }

    if (showImport) {
        AddByLinkDialog(
            onDismiss = { showImport = false },
            onImport = { url -> vm.import(url) { onOpenRecipe(it) }; showImport = false },
        )
    }
}

@Composable
private fun RecipeCard(r: Recipe, onClick: () -> Unit, onMove: (RecipeStatus) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            DishArt(r.title, r.ingredients.map { it.name }, r.imageUrl, Modifier.fillMaxWidth().height(110.dp), emojiSize = 44)
            if (r.favorite) {
                Text(
                    "⭐", modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                IconButton(onClick = { menu = true }) {
                    Text("⋯", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                MoveMenu(expanded = menu, current = r.status, onDismiss = { menu = false }) { menu = false; onMove(it) }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    r.timesCooked.takeIf { it > 0 }?.let { "cuisiné ${it}×" },
                    ((r.prepMinutes ?: 0) + (r.cookMinutes ?: 0)).takeIf { it > 0 }?.let { "$it min" },
                ).joinToString(" · ").ifEmpty { "${r.servings} pers." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Menu « Déplacer vers… » une autre liste du livre. */
@Composable
fun MoveMenu(expanded: Boolean, current: RecipeStatus, onDismiss: () -> Unit, onMove: (RecipeStatus) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RecipeStatus.entries.filter { it != current }.forEach { s ->
            DropdownMenuItem(text = { Text("${s.emoji}  Déplacer vers « ${s.label} »") }, onClick = { onMove(s) })
        }
    }
}

@Composable
fun RecipeDetailScreen(recipeId: Long, onBack: () -> Unit, onEdit: () -> Unit, onSendToCart: () -> Unit) {
    val vm = containerViewModel(key = "recipe-$recipeId") { RecipeDetailViewModel(it, recipeId) }
    val recipe by vm.recipe.collectAsStateWithLifecycle()
    val portions by vm.portions.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val stepsState by vm.steps.collectAsStateWithLifecycle()
    val modelStatus by vm.model.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val checked = remember { mutableStateListOf<Int>() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    val r = recipe
    ScreenScaffold(
        title = r?.title ?: "Recette",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (r != null) {
                IconButton(onClick = { vm.sendToCart(r); onSendToCart() }) { Icon(Icons.Filled.ShoppingCart, "Envoyer au panier") }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Modifier") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteOutline, "Supprimer") }
            }
        },
        bottomBar = {
            if (r != null) BottomActionBar(Modifier) {
                OutlinedButton(onClick = { vm.addToMenu(r) }, enabled = !r.banned) { Text("Au menu") }
                Button(onClick = { vm.addToList(r) }, modifier = Modifier.weight(1f)) { Text("🛒 Ajouter à la liste") }
            }
        },
    ) { padding ->
        if (r == null) return@ScreenScaffold
        val present = PlateBalance.components(listOf(r.title) + r.ingredients.map { it.name })
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            DishArt(
                r.title, r.ingredients.map { it.name }, r.imageUrl,
                Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.extraLarge),
                emojiSize = 84,
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                r.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.prepMinutes?.let { StatChip("🔪 $it min") }
                    r.cookMinutes?.let { StatChip("🔥 $it min") }
                    if (r.timesCooked > 0) StatChip("🍽 cuisiné ${r.timesCooked}×")
                    StatChip("Recette pour ${r.servings}")
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RecipeStatus.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = r.status == s,
                            onClick = { if (r.status != s) vm.move(r, s) },
                            shape = SegmentedButtonDefaults.itemShape(i, RecipeStatus.entries.size),
                            icon = {},
                            label = { Text("${s.emoji} ${if (s == RecipeStatus.BANNI) "Exclu" else s.label}", maxLines = 1) },
                        )
                    }
                }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Assiette", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        BalanceDots(present)
                    }
                    balanceHint(present)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    r.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                SectionCard {
                    Text("Pour combien ?", style = MaterialTheme.typography.titleSmall)
                    PillStepper("🧑", "Adultes", portions.adults, { vm.portions.value = portions.copy(adults = it.coerceAtLeast(0)) })
                    PillStepper("🧒", "Enfants", portions.children, { vm.portions.value = portions.copy(children = it.coerceAtLeast(0)) })
                }
                SectionHeader("Ingrédients", emoji = "🧺")
                ShoppingListMerger.scale(r, portions.equivalent(vm.childFactor())).forEachIndexed { i, ing ->
                    val done = i in checked
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable { if (done) checked.remove(i) else checked.add(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = done, onCheckedChange = { if (it) checked.add(i) else checked.remove(i) })
                        val q = ing.quantity?.let { ShoppingListMerger.formatNumber(it) }
                        Text(
                            listOfNotNull(q, ing.unit, ing.name).joinToString(" "),
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                SectionHeader("Préparation", emoji = "👩‍🍳")
                if (r.steps.isEmpty()) {
                    when (val st = stepsState) {
                        RecipeDetailViewModel.StepsState.Fetching ->
                            ChefCard("Recherche des étapes sur le site…", "Récupération de la recette d'origine.", emoji = "🌐")
                        RecipeDetailViewModel.StepsState.Writing ->
                            if (modelStatus == ModelStatus.Loading) ModelLoadingCard()
                            else ChefCard("Le chef écrit la recette…", "Étapes et temps de cuisson, à partir des ingrédients ci-dessus.")
                        is RecipeDetailViewModel.StepsState.Queued -> ChefCard(
                            "En attente du chef…",
                            if (st.position <= 1) "C'est la prochaine recette qu'il écrit." else "${st.position - 1} recette(s) avant celle-ci.",
                            emoji = "⏳",
                        )
                        is RecipeDetailViewModel.StepsState.Failed -> Column {
                            Text(st.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::writeSteps) { Text("Réessayer") }
                        }
                        RecipeDetailViewModel.StepsState.Idle -> if (MenuValidator.STEPS_PENDING in r.tags) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Les étapes n'ont pas pu être lues sur le site. Le chef peut compléter les données manquantes à partir des ingrédients.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = vm::writeSteps, enabled = modelStatus != ModelStatus.Absent) {
                                    Text("✍️ Générer les données manquantes avec le chef")
                                }
                                if (modelStatus == ModelStatus.Absent) {
                                    Text("Installe l'assistant dans les Réglages pour l'utiliser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        } else {
                            Text("Pas d'étapes pour cette recette.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                r.steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                    }
                }
                r.sourceUrl?.let { url ->
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                        Text("Voir la recette d'origine", Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer cette recette ?") },
        text = { Text("Pour que l'assistant ne la propose plus, mieux vaut la passer en « Ne plus proposer ».") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
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
    val shape = RoundedCornerShape(16.dp)
    ScreenScaffold(
        title = if (recipeId == 0L) "Nouvelle recette" else "Modifier la recette",
        onBack = onDone,
        bottomBar = {
            BottomActionBar {
                TextButton(onClick = onDone) { Text("Annuler") }
                Button(onClick = { vm.save(onDone) }, enabled = title.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Enregistrer") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DishArt(title.ifBlank { "Nouvelle recette" }, modifier = Modifier.fillMaxWidth().height(120.dp).clip(MaterialTheme.shapes.extraLarge))
            SectionCard {
                OutlinedTextField(title, { vm.title.value = it }, label = { Text("Titre") }, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    servings, { vm.servings.value = it.filter(Char::isDigit) }, label = { Text("Pour combien de personnes ?") }, shape = shape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
            }
            SectionCard {
                Text("🧺 Ingrédients", style = MaterialTheme.typography.titleSmall)
                Text("Un par ligne, avec la quantité : « 200 g de farine », « 2 œufs ».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(ingredients, { vm.ingredients.value = it }, minLines = 6, shape = shape, modifier = Modifier.fillMaxWidth())
            }
            SectionCard {
                Text("👩‍🍳 Étapes", style = MaterialTheme.typography.titleSmall)
                Text("Une étape par ligne.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(steps, { vm.steps.value = it }, minLines = 6, shape = shape, modifier = Modifier.fillMaxWidth())
            }
            SectionCard {
                OutlinedTextField(tags, { vm.tags.value = it }, label = { Text("Tags (séparés par des virgules)") }, shape = shape, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}


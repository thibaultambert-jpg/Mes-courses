@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.discover

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.BottomActionBar
import fr.mescourses.app.ui.components.DishArt
import fr.mescourses.app.ui.components.EmptyState
import fr.mescourses.app.llm.ModelStatus
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.core.model.Portions
import fr.mescourses.core.shopping.ShoppingListMerger
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.PillStepper
import fr.mescourses.app.ui.components.SectionHeader
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.sp
import fr.mescourses.core.recipes.FilterGroup
import fr.mescourses.core.recipes.QuickIdea
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeFilters
import fr.mescourses.core.recipes.RecipeSort
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeSites
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------------------------
// Onglet « Découvrir »
// ---------------------------------------------------------------------------------------------

/** Contenu de l'onglet « Découvrir » du livre de recettes. */
@Composable
fun DiscoverContent(
    snackbar: SnackbarHostState,
    onOpenPreview: (String) -> Unit,
    onBrowse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { DiscoverViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val popular by vm.popularMode.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val inBook by vm.favorites.byUrl.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val sort by vm.sort.collectAsStateWithLifecycle()
    val localOnly by vm.localOnly.collectAsStateWithLifecycle()
    val searchingOnline by vm.searchingOnline.collectAsStateWithLifecycle()
    val reloading by vm.reloading.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    fun submit() { focus.clearFocus(); vm.search() }

    // Tirer l'écran vers le bas relance la recherche : une nouvelle liste de recettes.
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = reloading,
        onRefresh = { vm.reload() },
        modifier = modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. Recherche + bouton Filtres (avec le nombre de filtres actifs).
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Effacer") }
                        },
                        placeholder = { Text("Un plat, un ingrédient…") },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    val active = filters.size + (if (sites.isNotEmpty()) 1 else 0) + (if (sort != RecipeSort.PERTINENCE) 1 else 0)
                    BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                        FilledTonalIconButton(onClick = { focus.clearFocus(); showFilters = true }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Filled.Tune, "Filtres")
                        }
                    }
                }
                // 2. « Je cherche… » : grandes tuiles par type de plat (une touche cherche, une 2ᵉ retire).
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RecipeFilters.tiles, key = { it.name }) { f ->
                        val selected = f in filters
                        Surface(
                            onClick = { focus.clearFocus(); vm.toggleFilter(f) },
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(width = 78.dp, height = 74.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(f.emoji, fontSize = 26.sp)
                                Text(f.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
                // 3. Filtres actifs (supprimables) et Populaires.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    item {
                        FilterChip(selected = popular, onClick = { focus.clearFocus(); vm.togglePopular() }, label = { Text("🔥 Populaires") })
                    }
                    items(filters.sortedBy { it.ordinal }.filterNot { it in RecipeFilters.tiles }, key = { it.name }) { f ->
                        InputChip(
                            selected = true,
                            onClick = { vm.toggleFilter(f) },
                            label = { Text("${f.emoji} ${f.label}") },
                            trailingIcon = { Icon(Icons.Filled.Close, "Retirer ${f.label}", Modifier.size(16.dp)) },
                        )
                    }
                    if (filters.isNotEmpty()) item { TextButton(onClick = { vm.clearFilters() }) { Text("Tout effacer") } }
                }
            }
        }

        when (val s = state) {
            DiscoverViewModel.State.Idle -> {
                item(span = { GridItemSpan(2) }) {
                    IdleDiscover(
                        ideas = vm.seasonIdeas,
                        quickIdeas = vm.quickIdeas,
                        onIdea = { focus.clearFocus(); vm.searchIdea(it) },
                        onQuickIdea = { focus.clearFocus(); vm.applyIdea(it) },
                        onBrowse = onBrowse,
                    )
                }
            }
            DiscoverViewModel.State.Loading -> {
                // Pendant un rechargement, l'indicateur du geste suffit.
                if (!reloading) item(span = { GridItemSpan(2) }) {
                    Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            when {
                                popular -> "Recettes populaires du moment…"
                                else -> "Recherche…"
                            },
                            Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            is DiscoverViewModel.State.Done -> {
                val r = s.result
                item(span = { GridItemSpan(2) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val answered = r.hits.map { it.siteName }.distinct().size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (r.hits.isEmpty()) "Aucune recette trouvée"
                            else (if (popular) "🔥 Populaires en ce moment · " else "") +
                                "${r.hits.size} recettes · $answered site${if (answered > 1) "s" else ""}" +
                                (if (localOnly) " · catalogue" else "") +
                                if (sort != RecipeSort.PERTINENCE) " · ${sort.label.lowercase()}" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.reload() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Text("Nouvelle liste", Modifier.padding(start = 4.dp))
                        }
                        }
                        // Résultats du catalogue local : la recherche en ligne reste possible, en complément.
                        if (searchingOnline) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Recherche sur les sites…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (localOnly) {
                            OutlinedButton(onClick = { vm.searchOnline() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                Text("Chercher aussi sur les sites")
                            }
                        }
                        // Site muet ou illisible : on propose d'ouvrir sa recherche dans le navigateur de l'app.
                        (r.failures.keys + r.empty).forEach { site ->
                            Text(
                                "${site.name} : ${if (site in r.failures) "pas de réponse" else "aucun résultat lisible"} — ouvrir sur le site ›",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBrowse(vm.searchUrl(site)) }.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                if (r.hits.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        EmptyState("🔎", "Rien trouvé", "Essaie un autre mot (un ingrédient, un plat), ou retire un filtre.")
                    }
                }
                items(r.hits, key = { it.url }) { hit ->
                    val book = inBook[RecipeSites.normalize(hit.url)]
                    HitCard(
                        hit = hit,
                        badge = RecipeFilters.badge(hit.title),
                        favorite = book?.status == RecipeStatus.FAVORI,
                        adding = hit.url in adding,
                        onClick = { onOpenPreview(hit.url) },
                        onStar = { vm.addFavorite(hit) },
                    )
                }
            }
        }
    }
    }

    if (showFilters) FiltersSheet(vm, onDismiss = { showFilters = false })
}

/** Feuille « Filtres » : groupes de filtres, sites et tri ; « Voir les recettes » lance la recherche. */
@Composable
private fun FiltersSheet(vm: DiscoverViewModel, onDismiss: () -> Unit) {
    val filters by vm.filters.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val counts by vm.catalogCounts.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (filters.isNotEmpty()) TextButton(onClick = { filters.forEach { vm.toggleFilterQuietly(it) } }) { Text("Tout effacer") }
            }
            FilterGroup.entries.forEach { group ->
                Text(group.label + if (group.single) " (un seul)" else "", style = MaterialTheme.typography.titleSmall)
                val inGroup = RecipeFilter.entries.filter { it.group == group }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    inGroup.forEach { f ->
                        FilterChip(selected = f in filters, onClick = { vm.toggleFilterQuietly(f) }, label = { Text("${f.emoji} ${f.label}") })
                    }
                }
                inGroup.filter { it in filters && it.note != null }.forEach { f ->
                    Text("${f.label} : ${f.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Trier par", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecipeSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { vm.setSort(s) }, label = { Text(s.label) }) }
            }
            Text("Sites", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(selected = sites.isEmpty(), onClick = { vm.allSites() }, label = { Text("Tous") })
                vm.allSitesList.forEach { site ->
                    val n = counts[site.id]
                    FilterChip(
                        selected = site.id in sites,
                        onClick = { vm.toggleSite(site.id) },
                        label = { Text(if (n != null && n > 0) "${site.name} · $n" else site.name) },
                    )
                }
            }
            Text(
                "Le nombre indique les recettes déjà dans le catalogue du téléphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.refresh(); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Voir les recettes") }
        }
    }
}

@Composable
private fun IdleDiscover(
    ideas: List<String>,
    quickIdeas: List<QuickIdea>,
    onIdea: (String) -> Unit,
    onQuickIdea: (QuickIdea) -> Unit,
    onBrowse: (String) -> Unit,
) {
    var browseOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        SectionHeader("Idées rapides", emoji = "✨")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            quickIdeas.forEach { idea ->
                AssistChip(onClick = { onQuickIdea(idea) }, label = { Text("${idea.emoji} ${idea.label}") })
            }
        }
        if (ideas.isNotEmpty()) {
            SectionHeader("De saison en ce moment", emoji = "🍂")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ideas.forEach { idea ->
                    FilterChip(
                        selected = false,
                        onClick = { onIdea(idea) },
                        label = { Text(idea.replaceFirstChar { it.uppercase() }) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    )
                }
            }
        }
        // Parcourir un site : replié par défaut, pour laisser la place aux idées.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { browseOpen = !browseOpen }, verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Parcourir un site", emoji = "🧭", modifier = Modifier.weight(1f))
            Text(if (browseOpen) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        }
        if (browseOpen) {
            Text(
                "Navigue sur le site dans l'app : chaque recette ouverte s'affiche en version simplifiée, prête à ajouter à tes favoris.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RecipeSites.all.forEach { site ->
                    OutlinedButton(onClick = { onBrowse(site.browsePage) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(site.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: RecipeHit, badge: RecipeFilter?, favorite: Boolean, adding: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            DishArt(hit.title, imageUrl = hit.imageUrl, modifier = Modifier.fillMaxWidth().height(110.dp), emojiSize = 40)
            Text(
                hit.siteName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            // Pastille de catégorie reconnue dans le titre (Poisson, Dessert…).
            badge?.let { b ->
                Text(
                    "${b.emoji} ${b.label}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(40.dp).clip(CircleShape).clickable(onClick = onStar),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (adding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(
                        if (favorite) "★" else "☆",
                        color = if (favorite) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                hit.totalMinutes?.let { "$it min" },
                hit.rating?.let { r -> "★ ${"%.1f".format(r).replace('.', ',')}" + (hit.ratingCount?.let { " ($it)" } ?: "") },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Aperçu d'une recette
// ---------------------------------------------------------------------------------------------

@Composable
fun RecipePreviewScreen(url: String, onBack: () -> Unit, onOpenRecipe: (Long) -> Unit, onBrowse: (String) -> Unit) {
    val vm = containerViewModel(key = "preview-$url") { RecipePreviewViewModel(it, url) }
    val state by vm.state.collectAsStateWithLifecycle()
    val inBook by vm.favorites.byUrl.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val portions by vm.portions.collectAsStateWithLifecycle()
    val chefStatus by vm.chefStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }
    val book = inBook[RecipeSites.normalize(url)]
    val siteName = vm.site?.name ?: RecipeSites.hostOf(url) ?: "le site"

    ScreenScaffold(
        title = (state as? RecipePreviewViewModel.State.Loaded)?.recipe?.title ?: "Aperçu",
        onBack = onBack,
        snackbar = snackbar,
        actions = { IconButton(onClick = { onBrowse(url) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Voir sur $siteName") } },
        bottomBar = {
            val r = (state as? RecipePreviewViewModel.State.Loaded)?.recipe
            if (r != null) BottomActionBar {
                if (book?.status == RecipeStatus.FAVORI) {
                    Button(onClick = { onOpenRecipe(book.id) }, modifier = Modifier.weight(1f)) { Text("⭐ Dans tes favoris · Ouvrir") }
                } else {
                    Button(onClick = { vm.addFavorite(r) }, modifier = Modifier.weight(1f)) { Text("⭐ Ajouter aux favoris") }
                }
            }
        },
    ) { padding ->
        when (val s = state) {
            RecipePreviewViewModel.State.Loading -> Column(
                Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text("Lecture de la recette sur $siteName…", Modifier.padding(top = 12.dp))
            }
            is RecipePreviewViewModel.State.Failed -> Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState("😕", "Recette illisible", s.message, actionLabel = "Ouvrir la page dans l'app", onAction = { onBrowse(url) })
                TextButton(onClick = { vm.load() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Réessayer") }
            }
            is RecipePreviewViewModel.State.Loaded -> PreviewBody(
                r = s.recipe,
                siteName = siteName,
                offline = offline,
                portions = portions,
                childFactor = vm.childFactor(),
                onPortions = { vm.setPortions(it) },
                modifier = Modifier.padding(padding),
                onMenu = { vm.addToMenu(s.recipe) },
                onList = { vm.addToList(s.recipe) },
                onSite = { onBrowse(url) },
                chefInstalled = chefStatus != ModelStatus.Absent,
                onGenerate = { vm.generateSteps(s.recipe, onOpenRecipe) },
            )
        }
    }
}

@Composable
private fun PreviewBody(r: Recipe, siteName: String, offline: Boolean, portions: Portions, childFactor: Double, onPortions: (Portions) -> Unit, modifier: Modifier, onMenu: () -> Unit, onList: () -> Unit, onSite: () -> Unit, chefInstalled: Boolean, onGenerate: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DishArt(
            r.title, r.ingredients.map { it.name }, r.imageUrl,
            Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.extraLarge),
            emojiSize = 84,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (offline) InfoBanner("📴", "Hors ligne : informations déjà connues (ingrédients, temps). Les étapes seront lues sur le site dès que tu auras internet.")
            Text(r.title, style = MaterialTheme.typography.headlineSmall)
            Text("sur $siteName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.prepMinutes?.let { StatChip("🔪 $it min") }
                r.cookMinutes?.let { StatChip("🔥 $it min") }
                fr.mescourses.app.ui.components.ServingsChip("👥 ${r.servings} pers.", r.servings, portions)
                StatChip("🧺 ${r.ingredients.size} ingrédients")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMenu, modifier = Modifier.weight(1f)) { Text("🍽 Au menu") }
                OutlinedButton(onClick = onList, modifier = Modifier.weight(1f)) { Text("📝 À la liste") }
            }
            SectionCard {
                Text("Pour combien ?", style = MaterialTheme.typography.titleSmall)
                PillStepper("🧑", "Adultes", portions.adults, { onPortions(portions.copy(adults = it.coerceAtLeast(0))) })
                PillStepper("🧒", "Enfants", portions.children, { onPortions(portions.copy(children = it.coerceAtLeast(0))) })
            }
            SectionHeader("Ingrédients", emoji = "🧺")
            // Quantités recalculées pour le nombre de personnes choisi (celui des Réglages au départ).
            ShoppingListMerger.scale(r, portions.equivalent(childFactor)).forEach { ing ->
                Row(Modifier.fillMaxWidth()) {
                    Text("•", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                    val q = ing.quantity?.let { ShoppingListMerger.formatNumber(it) }
                    Text(
                        if (q == null && ing.unit == null) ing.raw else listOfNotNull(q, ing.unit, ing.name).joinToString(" "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (r.steps.isNotEmpty()) {
                SectionHeader("Préparation", emoji = "👩‍🍳")
                r.steps.forEachIndexed { i, step ->
                    Row {
                        Text("${i + 1}.", Modifier.padding(end = 8.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (r.steps.isEmpty()) {
                Button(onClick = onGenerate, enabled = chefInstalled, modifier = Modifier.fillMaxWidth()) {
                    Text("✍️ Générer les données manquantes avec le chef")
                }
                Text(
                    if (chefInstalled) "Ajoute la recette à tes favoris puis le chef écrit les étapes, seulement sur ton clic."
                    else "Installe l'assistant dans les Réglages pour l'utiliser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSite) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                Text("Voir sur $siteName", Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Navigateur intégré avec détection de recette
// ---------------------------------------------------------------------------------------------

/** Lit le contenu des balises JSON-LD de la page (données schema.org/Recipe). */
/**
 * Réglages proches de Chrome pour les sites de recettes (Marmiton, 750g…) : sans eux, le menu ne s'ouvre pas
 * ou s'affiche cassé (fenêtre de consentement aux cookies tiers bloquée, mise en page « bureau », popups ignorées).
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configureLikeChrome(wv: WebView) {
    with(wv.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        @Suppress("DEPRECATION")
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = true
        javaScriptCanOpenWindowsAutomatically = true
        // Les liens « nouvelle fenêtre » s'ouvrent dans la même vue.
        setSupportMultipleWindows(false)
        builtInZoomControls = true
        displayZoomControls = false
        userAgentString = chromeUserAgent(userAgentString)
    }
    CookieManager.getInstance().setAcceptCookie(true)
    // Bandeaux de consentement (Didomi, Sourcepoint…) servis depuis un autre domaine.
    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    wv.webChromeClient = object : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }
    }
}

/** User-Agent identique à Chrome pour Android (voir [RecipeSites.chromeUserAgent]). */
fun chromeUserAgent(webViewUa: String): String = RecipeSites.chromeUserAgent(webViewUa)

private const val READ_JSON_LD =
    "JSON.stringify(Array.prototype.map.call(document.querySelectorAll('script[type=\"application/ld+json\"]'),function(s){return s.textContent;}))"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RecipeBrowserScreen(startUrl: String, onClose: () -> Unit, onOpenRecipe: (Long) -> Unit, onSimplify: (String) -> Unit = {}) {
    val vm = containerViewModel(key = "browser") { RecipeBrowserViewModel(it) }
    val detected by vm.detected.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val inBook by vm.favorites.byUrl.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }
    LaunchedEffect(Unit) { vm.clear() }

    fun isRecipe(url: String) = RecipeSites.forUrl(url)?.isRecipeUrl(url) == true
    var lastSimplified by remember { mutableStateOf<Pair<String, Long>?>(null) }
    fun simplify(url: String) {
        // Évite d'ouvrir deux fois le même aperçu (deux événements pour une même navigation).
        val now = System.currentTimeMillis()
        if (lastSimplified?.let { it.first == url && now - it.second < 2_000 } == true) return
        lastSimplified = url to now
        onSimplify(url)
    }

    fun scan(v: WebView) {
        val url = v.url ?: return
        v.evaluateJavascript(READ_JSON_LD) { raw ->
            val blocks = runCatching {
                val text = Json.decodeFromString(String.serializer(), raw)
                Json.decodeFromString(ListSerializer(String.serializer()), text)
            }.getOrDefault(emptyList())
            vm.onPage(url, blocks)
            // Filet de sécurité : page de recette dont l'adresse n'est pas reconnue (Picnic, site qui a changé
            // ses adresses…) mais qui contient une recette → version simplifiée, en revenant à la liste.
            val found = vm.detected.value
            if (found != null && found.sourceUrl == url && url != startUrl && !isRecipe(url) && v.url == url) {
                if (v.canGoBack()) v.goBack()
                simplify(url)
            }
        }
    }

    BackHandler {
        val v = view
        if (v != null && v.canGoBack()) v.goBack() else onClose()
    }

    ScreenScaffold(
        title = RecipeSites.forUrl(currentUrl)?.name ?: RecipeSites.hostOf(currentUrl) ?: "Parcourir",
        onBack = onClose,
        snackbar = snackbar,
        actions = {
            // Page de recette mal affichée : version simplifiée lue par l'app (ingrédients, étapes).
            if (RecipeSites.forUrl(currentUrl)?.isRecipeUrl(currentUrl) == true) {
                TextButton(onClick = { onSimplify(currentUrl) }) { Text("Simplifier") }
            }
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Ouvrir dans le navigateur")
            }
            TextButton(onClick = onClose) { Text("Terminé") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            configureLikeChrome(wv)
                            wv.webViewClient = object : WebViewClient() {
                                // Toute recette ouverte depuis le site s'affiche en version simplifiée (aperçu de l'app).
                                // La page de départ, elle, reste visible si on a demandé explicitement le site.
                                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (!request.isForMainFrame || url == startUrl || !isRecipe(url)) return false
                                    simplify(url)
                                    return true
                                }

                                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                    // Changement de page par JavaScript (location.href) vers une recette : on reste sur la liste.
                                    if (url != null && url != startUrl && isRecipe(url)) {
                                        v?.stopLoading()
                                        simplify(url)
                                        return
                                    }
                                    loading = true
                                    url?.let { currentUrl = it }
                                }

                                override fun onPageFinished(v: WebView?, url: String?) {
                                    loading = false
                                    v ?: return
                                    scan(v)
                                    // Certains sites ajoutent la fiche recette après le chargement.
                                    scope.launch { delay(1_500); view?.let(::scan) }
                                }

                                override fun doUpdateVisitedHistory(v: WebView?, url: String?, isReload: Boolean) {
                                    // Navigation interne du site (sans rechargement) vers une recette : version simplifiée,
                                    // et le navigateur revient à la liste pour le retour arrière.
                                    if (url != null && url != startUrl && isRecipe(url)) {
                                        if (v?.canGoBack() == true) v.goBack()
                                        simplify(url)
                                        return
                                    }
                                    url?.let { currentUrl = it }
                                    v?.let { w -> scope.launch { delay(800); scan(w) } }
                                }
                            }
                            // Retour depuis une recette simplifiée : on restaure l'historique au lieu de repartir de la page de départ.
                            val saved = vm.webState
                            if (saved == null || wv.restoreState(saved) == null) wv.loadUrl(startUrl)
                            view = wv
                        }
                    },
                    onRelease = { wv ->
                        vm.webState = android.os.Bundle().also { wv.saveState(it) }
                        wv.destroy()
                    },
                )
            }
            val r = detected
            if (r != null) {
                val book = r.sourceUrl?.let { inBook[RecipeSites.normalize(it)] }
                DetectedBanner(
                    recipe = r,
                    inFavorites = book?.status == RecipeStatus.FAVORI,
                    saving = saving,
                    onAdd = { vm.add(r) },
                    onOpen = { book?.let { onOpenRecipe(it.id) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun DetectedBanner(recipe: Recipe, inFavorites: Boolean, saving: Boolean, onAdd: () -> Unit, onOpen: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DishArt(recipe.title, recipe.ingredients.map { it.name }, recipe.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), emojiSize = 24)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "✓ Recette détectée · ${recipe.ingredients.size} ingrédients",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                saving -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                inFavorites -> OutlinedButton(onClick = onOpen) { Text("Ouvrir") }
                else -> Button(onClick = onAdd) { Text("⭐ Ajouter") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ajout par lien
// ---------------------------------------------------------------------------------------------

/** Lien http(s) actuellement dans le presse-papiers, s'il y en a un. */
private fun clipboardUrl(context: Context): String? {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        (0 until clip.itemCount).firstNotNullOfOrNull { i -> RecipeSites.findUrl(clip.getItemAt(i).coerceToText(context)?.toString()) }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun AddByLinkDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    val copied = remember { clipboardUrl(context) }
    val clean = RecipeSites.findUrl(url) ?: url.trim()
    val site = RecipeSites.forUrl(clean)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Link, null) },
        title = { Text("Ajouter une recette par lien") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (copied != null && copied != clean) {
                    InfoBanner(
                        "📋",
                        "Lien copié : ${copied.removePrefix("https://").removePrefix("www.").take(48)}… — appuie pour l'utiliser",
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { url = copied },
                        color = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("Adresse de la page") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (clean.startsWith("http")) onImport(clean) }),
                )
                if (clean.startsWith("http")) {
                    Text(
                        if (site != null) "✓ Site compatible (${site.name})" else "Site non reconnu : l'app essaiera quand même de lire la recette.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (site != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (site != null) FontWeight.Bold else null,
                    )
                }
                Text(
                    "Astuce : depuis Chrome, « Partager » → Mes courses fait la même chose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(enabled = clean.startsWith("http"), onClick = { onImport(clean) }) { Text("Importer dans les favoris") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.discover

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.app.ui.components.SectionHeader
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
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
    val adding by vm.adding.collectAsStateWithLifecycle()
    val inBook by vm.favorites.byUrl.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    fun submit() { focus.clearFocus(); vm.search() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Effacer") }
                    },
                    placeholder = { Text("Chercher sur les sites (ex. potimarron)") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = sites.isEmpty(), onClick = { vm.allSites() }, label = { Text("Tous les sites") })
                    }
                    items(vm.searchableSites, key = { it.id }) { s ->
                        FilterChip(selected = s.id in sites, onClick = { vm.toggleSite(s.id) }, label = { Text(s.name) })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DiscoverViewModel.Filter.entries.toList()) { f ->
                        FilterChip(selected = f in filters, onClick = { vm.toggleFilter(f) }, label = { Text(f.label) })
                    }
                }
            }
        }

        when (val s = state) {
            DiscoverViewModel.State.Idle -> {
                item(span = { GridItemSpan(2) }) {
                    IdleDiscover(
                        ideas = vm.seasonIdeas,
                        onIdea = { focus.clearFocus(); vm.searchIdea(it) },
                        onBrowse = onBrowse,
                    )
                }
            }
            DiscoverViewModel.State.Loading -> {
                item(span = { GridItemSpan(2) }) {
                    Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Recherche sur les sites…", Modifier.padding(start = 12.dp))
                    }
                }
            }
            is DiscoverViewModel.State.Done -> {
                val r = s.result
                item(span = { GridItemSpan(2) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val answered = r.hits.map { it.siteName }.distinct().size
                        Text(
                            if (r.hits.isEmpty()) "Aucune recette trouvée"
                            else "${r.hits.size} recettes · $answered site${if (answered > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

@Composable
private fun IdleDiscover(ideas: List<String>, onIdea: (String) -> Unit, onBrowse: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
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
        SectionHeader("Parcourir un site", emoji = "🧭")
        Text(
            "Navigue sur le site dans l'app : dès qu'une page contient une recette, un bandeau te propose de l'ajouter à tes favoris.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RecipeSites.all.forEach { site ->
                OutlinedButton(onClick = { onBrowse(site.homeUrl) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(site.name)
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: RecipeHit, favorite: Boolean, adding: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
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
                modifier = Modifier.padding(padding),
                onMenu = { vm.addToMenu(s.recipe) },
                onList = { vm.addToList(s.recipe) },
                onSite = { onBrowse(url) },
            )
        }
    }
}

@Composable
private fun PreviewBody(r: Recipe, siteName: String, modifier: Modifier, onMenu: () -> Unit, onList: () -> Unit, onSite: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DishArt(
            r.title, r.ingredients.map { it.name }, r.imageUrl,
            Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.extraLarge),
            emojiSize = 84,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(r.title, style = MaterialTheme.typography.headlineSmall)
            Text("sur $siteName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.prepMinutes?.let { StatChip("🔪 $it min") }
                r.cookMinutes?.let { StatChip("🔥 $it min") }
                StatChip("👥 ${r.servings} pers.")
                StatChip("🧺 ${r.ingredients.size} ingrédients")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMenu, modifier = Modifier.weight(1f)) { Text("🍽 Au menu") }
                OutlinedButton(onClick = onList, modifier = Modifier.weight(1f)) { Text("📝 À la liste") }
            }
            SectionHeader("Ingrédients", emoji = "🧺")
            r.ingredients.forEach { ing ->
                Row(Modifier.fillMaxWidth()) {
                    Text("•", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                    Text(ing.raw, style = MaterialTheme.typography.bodyLarge)
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
private const val READ_JSON_LD =
    "JSON.stringify(Array.prototype.map.call(document.querySelectorAll('script[type=\"application/ld+json\"]'),function(s){return s.textContent;}))"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RecipeBrowserScreen(startUrl: String, onClose: () -> Unit, onOpenRecipe: (Long) -> Unit) {
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

    fun scan(v: WebView) {
        val url = v.url ?: return
        v.evaluateJavascript(READ_JSON_LD) { raw ->
            val blocks = runCatching {
                val text = Json.decodeFromString(String.serializer(), raw)
                Json.decodeFromString(ListSerializer(String.serializer()), text)
            }.getOrDefault(emptyList())
            vm.onPage(url, blocks)
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
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
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
                                    url?.let { currentUrl = it }
                                    v?.let { w -> scope.launch { delay(800); scan(w) } }
                                }
                            }
                            wv.loadUrl(startUrl)
                            view = wv
                        }
                    },
                    onRelease = { it.destroy() },
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

package fr.mescourses.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.flow.map
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.mescourses.app.ui.retail.RetailerDebugScreen
import fr.mescourses.app.ui.retail.RetailerWebScreen
import fr.mescourses.app.ui.retail.StoreScreen
import fr.mescourses.app.ui.retail.WebMode
import fr.mescourses.app.ui.retail.WriteConfirmationDialog
import fr.mescourses.app.ui.cart.CartScreen
import fr.mescourses.app.ui.cart.CompareScreen
import fr.mescourses.core.retail.Retailer
import androidx.compose.runtime.key
import fr.mescourses.app.ui.cookbook.CookbookScreen
import fr.mescourses.app.ui.cookbook.RecipeDetailScreen
import fr.mescourses.app.ui.cookbook.RecipeEditScreen
import fr.mescourses.app.ui.discover.RecipeBrowserScreen
import fr.mescourses.app.ui.discover.RecipePreviewScreen
import fr.mescourses.app.ui.menu.MenuScreen
import fr.mescourses.app.ui.settings.SettingsScreen
import fr.mescourses.app.ui.shopping.ShoppingListScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("menu", "Menu", Icons.Filled.RestaurantMenu),
    Tab("cookbook", "Recettes", Icons.AutoMirrored.Filled.MenuBook),
    Tab("shopping", "Liste", Icons.Filled.Checklist),
    Tab("cart", "Panier", Icons.Filled.ShoppingCart),
)

@Composable
fun AppRoot(sharedUrl: String?, onSharedUrlConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) nav.navigate("cookbook?import=${android.net.Uri.encode(sharedUrl)}")
    }

    fun goTab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val container = fr.mescourses.app.ui.common.appContainer()
    val draftCount by remember { container.draft.observe().map { it.size } }.collectAsStateWithLifecycle(0)
    val remaining by remember {
        container.shopping.observeAll().map { l -> l.filterNot { it.checked }.map { ShoppingListMerger.key(it.name) }.distinct().size }
    }.collectAsStateWithLifecycle(0)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (tabs.any { current?.startsWith(it.route) == true }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        val badge = when (tab.route) {
                            "menu" -> draftCount
                            "shopping" -> remaining
                            else -> 0
                        }
                        NavigationBarItem(
                            selected = current?.startsWith(tab.route) == true,
                            onClick = { goTab(tab.route) },
                            icon = {
                                BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // WebViews invisibles (1 px) qui exécutent les requêtes des drives ; recréées avec l'activité.
        // Seules celles de l'enseigne active (et des enseignes comparées) sont attachées.
        val attached by container.attachedRetailers.collectAsStateWithLifecycle()
        attached.sortedBy { it.ordinal }.forEach { r ->
            key(r) {
                AndroidView(
                    factory = { ctx -> container.bridge(r).createWebView(ctx) },
                    onRelease = { container.bridge(r).detach(it) },
                    modifier = Modifier.size(1.dp).alpha(0.01f),
                )
            }
        }
        WriteConfirmationDialog(container.confirmations)
        NavHost(nav, startDestination = "menu", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("menu") {
                MenuScreen(
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenRecipe = { nav.navigate("recipe/$it") },
                    onOpenShopping = { goTab("shopping") },
                )
            }
            composable(
                "cookbook?import={import}",
                arguments = listOf(navArgument("import") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                CookbookScreen(
                    importUrl = entry.arguments?.getString("import"),
                    onImportConsumed = onSharedUrlConsumed,
                    onOpenRecipe = { nav.navigate("recipe/$it") },
                    // Recette importée par « Partager » : on retire l'écran d'import de l'historique, sinon le
                    // bouton Retour y revient et relance l'import (boucle). Retour => liste des recettes.
                    onImported = { id ->
                        nav.navigate("cookbook") { popUpTo("cookbook?import={import}") { inclusive = true } }
                        nav.navigate("recipe/$id")
                    },
                    onNewRecipe = { nav.navigate("recipe/edit/0") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenPreview = { nav.navigate("recipes/preview?url=${android.net.Uri.encode(it)}") },
                    onBrowse = { nav.navigate("recipes/browse?url=${android.net.Uri.encode(it)}") },
                )
            }
            composable(
                "recipes/preview?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                RecipePreviewScreen(
                    url = entry.arguments?.getString("url").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenRecipe = { nav.navigate("recipe/$it") },
                    onBrowse = { nav.navigate("recipes/browse?url=${android.net.Uri.encode(it)}") },
                )
            }
            composable(
                "recipes/browse?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                RecipeBrowserScreen(
                    startUrl = entry.arguments?.getString("url").orEmpty(),
                    onClose = { nav.popBackStack() },
                    onOpenRecipe = { nav.navigate("recipe/$it") },
                    onSimplify = { nav.navigate("recipes/preview?url=${android.net.Uri.encode(it)}") },
                )
            }
            composable("shopping") {
                ShoppingListScreen(onOpenSettings = { nav.navigate("settings") }, onSendToCart = { goTab("cart") })
            }
            composable("cart") {
                CartScreen(
                    onOpenSettings = { nav.navigate("settings") },
                    onLogin = { r -> nav.navigate(webRoute(r, container.bridge(r).site.login(), WebMode.LOGIN)) },
                    onOpenSite = { r, url -> nav.navigate(webRoute(r, url, WebMode.SITE)) },
                    onChooseStore = { r -> nav.navigate("retail/store/${r.name}") },
                    onCompare = { nav.navigate("cart/compare") },
                )
            }
            composable("cart/compare") {
                CompareScreen(
                    onBack = { nav.popBackStack() },
                    onFilled = { nav.popBackStack() },
                    onLogin = { r -> nav.navigate(webRoute(r, container.bridge(r).site.login(), WebMode.LOGIN)) },
                    onChooseStore = { r -> nav.navigate("retail/store/${r.name}") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onLogin = { r -> nav.navigate(webRoute(r, container.bridge(r).site.login(), WebMode.LOGIN)) },
                    onChooseStore = { r -> nav.navigate("retail/store/${r.name}") },
                    onDebug = { nav.navigate("retail/debug") },
                )
            }
            composable(
                "retail/web?retailer={retailer}&url={url}&mode={mode}",
                arguments = listOf(
                    navArgument("retailer") { type = NavType.StringType; defaultValue = Retailer.CARREFOUR.name },
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = WebMode.SITE.name },
                ),
            ) { entry ->
                val r = Retailer.parse(entry.arguments?.getString("retailer"))
                RetailerWebScreen(
                    retailer = r,
                    startUrl = entry.arguments?.getString("url")?.ifBlank { null } ?: container.bridge(r).site.parking(),
                    mode = WebMode.valueOf(entry.arguments?.getString("mode") ?: WebMode.SITE.name),
                    onClose = { nav.popBackStack() },
                )
            }
            composable("retail/store/{retailer}", arguments = listOf(navArgument("retailer") { type = NavType.StringType })) { entry ->
                val r = Retailer.parse(entry.arguments?.getString("retailer"))
                StoreScreen(r, onBack = { nav.popBackStack() }, onOpenSite = { url -> nav.navigate(webRoute(r, url, WebMode.SITE)) })
            }
            composable("retail/debug") {
                RetailerDebugScreen(onBack = { nav.popBackStack() }, onOpenSite = { r -> nav.navigate(webRoute(r, container.bridge(r).site.parking(), WebMode.SITE)) })
            }
            composable("recipe/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                RecipeDetailScreen(
                    recipeId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("recipe/edit/$id") },
                    onSendToCart = { goTab("cart") },
                )
            }
            composable("recipe/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                RecipeEditScreen(
                    recipeId = entry.arguments!!.getLong("id"),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}

private fun webRoute(retailer: Retailer, url: String, mode: WebMode) =
    "retail/web?retailer=${retailer.name}&url=${android.net.Uri.encode(url)}&mode=${mode.name}"

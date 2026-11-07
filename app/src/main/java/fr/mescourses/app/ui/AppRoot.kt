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
import fr.mescourses.app.ui.cart.CartScreen
import fr.mescourses.app.ui.cookbook.CookbookScreen
import fr.mescourses.app.ui.cookbook.RecipeDetailScreen
import fr.mescourses.app.ui.cookbook.RecipeEditScreen
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
                    onNewRecipe = { nav.navigate("recipe/edit/0") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("shopping") { ShoppingListScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("cart") { CartScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable("recipe/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                RecipeDetailScreen(
                    recipeId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("recipe/edit/$id") },
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

package fr.mescourses.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
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
import fr.mescourses.app.ui.chat.ChatScreen
import fr.mescourses.app.ui.cookbook.CookbookScreen
import fr.mescourses.app.ui.cookbook.RecipeDetailScreen
import fr.mescourses.app.ui.cookbook.RecipeEditScreen
import fr.mescourses.app.ui.planning.PlanningScreen
import fr.mescourses.app.ui.settings.SettingsScreen
import fr.mescourses.app.ui.shopping.ShoppingListScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("chat", "Assistant", Icons.AutoMirrored.Filled.Chat),
    Tab("planning", "Planning", Icons.Filled.CalendarMonth),
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

    Scaffold(
        bottomBar = {
            if (tabs.any { current?.startsWith(it.route) == true }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current?.startsWith(tab.route) == true,
                            onClick = { goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "chat", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("chat") { ChatScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("planning") {
                PlanningScreen(onOpenRecipe = { nav.navigate("recipe/$it") }, onOpenSettings = { nav.navigate("settings") })
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

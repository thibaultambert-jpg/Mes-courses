@file:OptIn(ExperimentalMaterial3Api::class)

package fr.mescourses.app.ui.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.ui.common.EmptyState
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.cookbook.PlanDialog
import fr.mescourses.core.agent.DateResolver
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class PlanningViewModel(private val c: AppContainer) : ViewModel() {
    val meals: StateFlow<List<PlannedMeal>> = c.plan.observeFrom(LocalDate.now().toString())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recipes: StateFlow<List<Recipe>> = c.recipes.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableStateFlow<String?>(null)

    fun changeServings(meal: PlannedMeal, delta: Int) = viewModelScope.launch {
        c.plan.update(meal.copy(servings = (meal.servings + delta).coerceAtLeast(1)))
    }

    fun delete(meal: PlannedMeal) = viewModelScope.launch { c.plan.delete(meal.id) }

    fun plan(recipe: Recipe, date: LocalDate, slot: fr.mescourses.core.model.MealSlot) = viewModelScope.launch {
        c.plan.add(PlannedMeal(date = date.toString(), slot = slot, recipeId = recipe.id, recipeTitle = recipe.title, servings = c.settings.current.householdSize))
    }

    /** Ajoute à la liste les ingrédients des repas pas encore ajoutés (quantités ajustées). */
    fun addAllToShoppingList() = viewModelScope.launch {
        var count = 0
        for (meal in meals.value.filterNot { it.addedToList }) {
            val recipe = c.recipes.get(meal.recipeId) ?: continue
            c.shopping.add(ShoppingListMerger.fromRecipe(recipe, meal.servings))
            c.plan.update(meal.copy(addedToList = true))
            count++
        }
        messages.value = if (count == 0) "Tous les repas sont déjà dans la liste" else "Ingrédients de $count repas ajoutés à la liste"
    }
}

@Composable
fun PlanningScreen(onOpenRecipe: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm = containerViewModel { PlanningViewModel(it) }
    val meals by vm.meals.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickRecipe by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Recipe?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messages.value = null } }

    ScreenScaffold(
        title = "Planning des repas",
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
        floatingActionButton = { FloatingActionButton(onClick = { pickRecipe = true }) { Icon(Icons.Filled.Add, "Planifier une recette") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (meals.isEmpty()) {
                EmptyState("Aucun repas planifié", "Demande à l'assistant « planifie 4 dîners pour 2 », ou ajoute une recette du livre avec +.")
                return@Column
            }
            val pending = meals.count { !it.addedToList }
            Button(
                onClick = { vm.addAllToShoppingList() },
                enabled = pending > 0,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) { Text(if (pending > 0) "Ajouter les ingrédients de $pending repas à la liste" else "Tout est dans la liste ✓") }
            LazyColumn {
                meals.groupBy { it.date }.forEach { (date, dayMeals) ->
                    item(key = date) {
                        Text(
                            DateResolver.label(LocalDate.parse(date)).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                        )
                    }
                    items(dayMeals, key = { it.id }) { meal ->
                        ListItem(
                            overlineContent = { Text(meal.slot.label) },
                            headlineContent = { Text(meal.recipeTitle) },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { vm.changeServings(meal, -1) }) { Icon(Icons.Filled.Remove, "Moins") }
                                    Text("${meal.servings} pers.")
                                    IconButton(onClick = { vm.changeServings(meal, +1) }) { Icon(Icons.Filled.Add, "Plus") }
                                    if (meal.addedToList) Icon(Icons.Filled.CheckCircle, "Dans la liste", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            trailingContent = { IconButton(onClick = { vm.delete(meal) }) { Icon(Icons.Filled.Delete, "Retirer") } },
                            modifier = Modifier.clickable { onOpenRecipe(meal.recipeId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (pickRecipe) AlertDialog(
        onDismissRequest = { pickRecipe = false },
        title = { Text("Choisir une recette") },
        text = {
            if (recipes.isEmpty()) Text("Le livre de recettes est vide.")
            else LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(recipes, key = { it.id }) { r ->
                    ListItem(headlineContent = { Text(r.title) }, modifier = Modifier.clickable { chosen = r; pickRecipe = false })
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickRecipe = false }) { Text("Fermer") } },
    )
    chosen?.let { r -> PlanDialog(onDismiss = { chosen = null }) { date, slot -> vm.plan(r, date, slot); chosen = null } }
}

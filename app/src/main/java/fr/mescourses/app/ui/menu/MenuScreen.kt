package fr.mescourses.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.core.model.MenuDish

@Composable
fun MenuScreen(onOpenSettings: () -> Unit, onOpenRecipe: (Long) -> Unit) {
    val vm = containerViewModel { MenuViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val dishes by vm.dishes.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ready = state.model is ModelStatus.Ready

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(state.chat.size, dishes.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0 && (state.chat.isNotEmpty() || dishes.isNotEmpty())) listState.animateScrollToItem(count - 1)
    }

    val subtitle = when (val m = state.model) {
        is ModelStatus.Ready -> "${m.modelName} · ${m.backend}"
        ModelStatus.Loading -> "chargement du modèle…"
        ModelStatus.Absent -> "aucun modèle"
        is ModelStatus.Failed -> "erreur modèle"
    }

    ScreenScaffold(title = "Menu de la semaine", onOpenSettings = onOpenSettings, snackbar = snackbar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { ModelBanner(state.model, subtitle, onOpenSettings) { vm.loadModel() } }

                if (week.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("À cuisiner cette semaine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::clearWeek) { Text("Effacer") }
                        }
                    }
                    items(week, key = { "w${it.id}" }) { meal ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = meal.cooked, onCheckedChange = { vm.toggleCooked(meal) })
                            Column(Modifier.weight(1f)) {
                                TextButton(onClick = { onOpenRecipe(meal.recipeId) }, contentPadding = PaddingValues(0.dp)) {
                                    Text(meal.recipeTitle, textDecoration = if (meal.cooked) TextDecoration.LineThrough else null)
                                }
                                Text(meal.portions.label(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                item {
                    ConfigCard(
                        state = state,
                        hasDraft = dishes.isNotEmpty(),
                        enabled = ready && !state.busy,
                        onMeals = vm::setMeals, onAdults = vm::setAdults, onChildren = vm::setChildren,
                        onWishes = vm::setWishes, onPropose = vm::propose,
                    )
                }

                if (dishes.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Proposition", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::clearDraft, enabled = !state.busy) { Text("Tout effacer") }
                        }
                    }
                    itemsIndexed(dishes) { i, dish ->
                        DishCard(
                            number = i + 1,
                            dish = dish,
                            favorite = dish.recipeId != null && dish.recipeId in favorites,
                            enabled = !state.busy,
                            canAskLlm = ready,
                            onPortions = { da, dc -> vm.changePortions(i, da, dc) },
                            onAnother = { vm.anotherIdea(i) },
                            onRemove = { vm.removeDish(i) },
                        )
                    }
                }

                items(state.chat.takeLast(12)) { line -> ChatBubble(line) }

                state.validation?.let { v ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (v.current.isNotBlank()) "Écriture de la recette : ${v.current}" else "Finalisation…")
                                LinearProgressIndicator(
                                    progress = { v.done.toFloat() / v.total.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                Text("${v.done} / ${v.total}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (dishes.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("« ajoute 3 adultes et 2 enfants au plat 2 »") },
                        maxLines = 3,
                        enabled = ready && !state.busy,
                    )
                    if (state.busy && state.validation == null) {
                        IconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, "Arrêter") }
                    } else {
                        IconButton(onClick = { vm.send(input); input = "" }, enabled = ready && input.isNotBlank() && !state.busy) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                        }
                    }
                }
                Button(
                    onClick = vm::validate,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) { Text("Valider le menu : recettes + liste de courses") }
            }
        }
    }
}

@Composable
private fun ModelBanner(model: ModelStatus, subtitle: String, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    when (model) {
        is ModelStatus.Ready -> Text("Assistant local : $subtitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp))
            Text("Chargement du modèle en mémoire…", Modifier.padding(start = 8.dp))
        }
        ModelStatus.Absent -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Aucun modèle local n'est installé. Télécharge Gemma 4 dans les Réglages (2 à 4 Go, une seule fois).")
                Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) { Text("Ouvrir les réglages") }
            }
        }
        is ModelStatus.Failed -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Chargement impossible : ${model.message}")
                Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Réessayer") }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange(value - 1) }, enabled = enabled) { Icon(Icons.Filled.Remove, "Moins") }
        Text("$value", fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 24.dp))
        IconButton(onClick = { onChange(value + 1) }, enabled = enabled) { Icon(Icons.Filled.Add, "Plus") }
    }
}

@Composable
private fun ConfigCard(
    state: MenuUiState,
    hasDraft: Boolean,
    enabled: Boolean,
    onMeals: (Int) -> Unit,
    onAdults: (Int) -> Unit,
    onChildren: (Int) -> Unit,
    onWishes: (String) -> Unit,
    onPropose: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(if (hasDraft) "Nouvelle proposition" else "Préparer le menu", style = MaterialTheme.typography.titleMedium)
            Stepper("Nombre de repas", state.meals, onMeals, !state.busy)
            Stepper("Adultes par repas", state.adults, onAdults, !state.busy)
            Stepper("Enfants par repas", state.children, onChildren, !state.busy)
            OutlinedTextField(
                value = state.wishes,
                onValueChange = onWishes,
                label = { Text("Envies ou contraintes (facultatif)") },
                placeholder = { Text("ex. rapide le soir, un plat de poisson, végétarien…") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Text(
                "Toujours : plats sains, produits bio et de saison. Tes favoris peuvent revenir, les plats « Ne plus proposer » jamais.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = onPropose, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (state.busy && state.validation == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (hasDraft) "Proposer un autre menu" else "Proposer un menu")
            }
        }
    }
}

@Composable
private fun DishCard(
    number: Int,
    dish: MenuDish,
    favorite: Boolean,
    enabled: Boolean,
    canAskLlm: Boolean,
    onPortions: (Int, Int) -> Unit,
    onAnother: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number. ${dish.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (favorite) Icon(Icons.Filled.Star, "Favori", tint = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onAnother, enabled = enabled && canAskLlm) { Icon(Icons.Filled.Autorenew, "Autre idée") }
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Filled.Delete, "Retirer") }
            }
            if (dish.description.isNotBlank()) Text(dish.description, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Adultes", style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { onPortions(-1, 0) }, enabled = enabled) { Icon(Icons.Filled.Remove, "Un adulte de moins") }
                Text("${dish.portions.adults}")
                IconButton(onClick = { onPortions(1, 0) }, enabled = enabled) { Icon(Icons.Filled.Add, "Un adulte de plus") }
                Text("Enfants", style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { onPortions(0, -1) }, enabled = enabled) { Icon(Icons.Filled.Remove, "Un enfant de moins") }
                Text("${dish.portions.children}")
                IconButton(onClick = { onPortions(0, 1) }, enabled = enabled) { Icon(Icons.Filled.Add, "Un enfant de plus") }
            }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    if (line.tool) {
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (line.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            line.text.trim(),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(14.dp),
                )
                .padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

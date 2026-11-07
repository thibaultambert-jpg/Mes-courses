package fr.mescourses.app.ui.carrefour

import android.location.Geocoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.appContainer
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.core.carrefour.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Choix du drive : code postal → coordonnées (Geocoder Android) → drives Carrefour proches. */
@Composable
fun CarrefourStoreScreen(onBack: () -> Unit, onOpenSite: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var postalCode by remember { mutableStateOf("") }
    var stores by remember { mutableStateOf<List<Store>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() = scope.launch {
        loading = true; error = null
        try {
            @Suppress("DEPRECATION")
            val address = withContext(Dispatchers.IO) {
                Geocoder(context, Locale.FRANCE).getFromLocationName("$postalCode, France", 1)?.firstOrNull()
            } ?: error("Code postal introuvable")
            stores = c.carrefour.driveStores(address.latitude, address.longitude, postalCode, address.locality ?: "")
            if (stores.isEmpty()) error = "Aucun drive trouvé autour de $postalCode."
        } catch (e: Exception) {
            c.session.onError(e)
            error = e.message
        } finally {
            loading = false
        }
    }

    ScreenScaffold(title = "Mon drive Carrefour", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.storeName.isNotBlank()) item { StatChip("Actuel : ${settings.storeName}", emphasized = true) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = postalCode,
                        onValueChange = { postalCode = it.filter(Char::isDigit).take(5) },
                        label = { Text("Code postal") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { search() }, enabled = postalCode.length == 5 && !loading, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Chercher")
                    }
                }
            }
            if (loading) item { CircularProgressIndicator() }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(stores, key = { it.id }) { store ->
                val selected = store.id == settings.storeId
                Card(
                    onClick = { scope.launch { c.settings.update { it.copy(storeId = store.id, storeName = store.name) } } },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(store.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(store.address, store.city, store.distanceKm?.let { "%.1f km".format(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (selected) Text("✓ Drive choisi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                InfoBanner(
                    "ℹ️",
                    "Pour que Carrefour enregistre ce drive dans ton compte, choisis-le aussi une fois sur le site " +
                        "(le panier dépend du magasin). L'application lira ensuite automatiquement le drive dans ton panier.",
                )
            }
            item { OutlinedButton(onClick = onOpenSite) { Text("Choisir le drive sur carrefour.fr") } }
        }
    }
}

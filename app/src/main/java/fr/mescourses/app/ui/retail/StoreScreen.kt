package fr.mescourses.app.ui.retail

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import fr.mescourses.core.leclerc.LeclercClient
import fr.mescourses.core.retail.Retailer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Choix du drive. Carrefour : code postal → coordonnées (Geocoder Android) → drives proches.
 * E.Leclerc : code postal → recherche de magasins du site. Auchan : le drive se choisit sur auchan.fr
 * (route non relevée) et l'application lit ensuite le magasin dans le panier.
 */
@Composable
fun StoreScreen(retailer: Retailer, onBack: () -> Unit, onOpenSite: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var postalCode by remember { mutableStateOf("") }
    var stores by remember { mutableStateOf<List<Store>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val who = retailer.label
    val currentName = settings.storeName(retailer)
    val currentId = when (retailer) {
        Retailer.CARREFOUR -> settings.storeId
        Retailer.LECLERC -> settings.leclercStoreId
        Retailer.AUCHAN -> ""
    }

    fun search() = scope.launch {
        loading = true; error = null
        try {
            @Suppress("DEPRECATION")
            val address = withContext(Dispatchers.IO) {
                runCatching { Geocoder(context, Locale.FRANCE).getFromLocationName("$postalCode, France", 1)?.firstOrNull() }.getOrNull()
            }
            // Leclerc retrouve lui-même les coordonnées du code postal ; Carrefour a besoin du Geocoder.
            if (address == null && retailer == Retailer.CARREFOUR) error("Code postal introuvable")
            // Délai maximal : sans réponse du site, un message plutôt qu'une roue qui tourne sans fin.
            stores = withTimeoutOrNull(40_000) {
                c.backend(retailer).stores(address?.latitude ?: 0.0, address?.longitude ?: 0.0, postalCode, address?.locality ?: "")
            } ?: error("$who ne répond pas : ouvre le site une fois (bouton ci-dessous), puis réessaie.")
            if (stores.isEmpty()) error = "Aucun drive $who trouvé autour de $postalCode."
        } catch (e: Exception) {
            c.session(retailer).onError(e)
            error = e.message
        } finally {
            loading = false
        }
    }

    fun choose(store: Store) = scope.launch {
        // Nouveau drive : l'ancien contexte (identifiant de panier, recherches en cache) ne vaut plus.
        c.backend(retailer).forgetContext()
        when (retailer) {
            Retailer.CARREFOUR -> c.settings.update { it.copy(storeId = store.id, storeName = store.name, storeRef = store.ref.orEmpty(), basketServiceId = "", facilityServiceId = "") }
            Retailer.LECLERC -> LeclercClient.storeOf(store)?.let { s ->
                c.settings.update { it.copy(leclercStoreId = s.id, leclercNoPR = s.noPR, leclercHost = s.host, leclercStoreName = s.name, leclercPath = s.path) }
                // La session suit le drive ouvert dans le navigateur : on ouvre ce drive dans la WebView, puis on vérifie.
                runCatching { c.bridge(retailer).reloadAndWait() }
                c.session(retailer).check()
            }
            Retailer.AUCHAN -> Unit
        }
    }

    ScreenScaffold(title = "Mon drive $who", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (currentName.isNotBlank()) item { StatChip("Actuel : $currentName", emphasized = true) }
            if (retailer == Retailer.AUCHAN) {
                item {
                    InfoBanner(
                        "ℹ️",
                        "Chez Auchan, le drive se choisit sur le site (connecte-toi, puis « Choisir mon drive »). " +
                            "L'application lit ensuite le magasin dans ton panier.",
                    )
                }
                item { Button(onClick = { onOpenSite(c.bridge(retailer).site.storeChoice()) }) { Text("Choisir le drive sur auchan.fr") } }
                return@LazyColumn
            }
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
            // Clé unique : un même magasin peut revenir pour plusieurs services.
            itemsIndexed(stores, key = { i, st -> "${st.id}|${st.ref}|$i" }) { _, store ->
                val selected = store.id == currentId
                Card(
                    onClick = { choose(store) },
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
                    when (retailer) {
                        Retailer.LECLERC -> "Ta session Leclerc est liée à un drive : ouvre une fois le drive choisi sur le site " +
                            "(et connecte-toi) pour que le panier se remplisse au bon endroit."
                        else -> "Pour que Carrefour enregistre ce drive dans ton compte, choisis-le aussi une fois sur le site " +
                            "(le panier dépend du magasin). L'application lira ensuite automatiquement le drive dans ton panier."
                    },
                )
            }
            item { OutlinedButton(onClick = { onOpenSite(c.bridge(retailer).site.storeChoice()) }) { Text("Ouvrir le drive sur ${retailer.site}") } }
        }
    }
}

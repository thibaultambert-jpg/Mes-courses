package fr.mescourses.app.ui.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.app.PendingQuote
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel
import fr.mescourses.app.ui.components.InfoBanner
import fr.mescourses.app.ui.components.SectionCard
import fr.mescourses.app.ui.components.StatChip
import fr.mescourses.app.ui.retail.RetailerDot
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.cart.PriceComparison
import fr.mescourses.core.cart.RetailerQuote
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.retail.Retailer
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompareState(
    val label: String = "Liste de courses",
    val items: List<ShoppingItem> = emptyList(),
    val quotes: Map<Retailer, RetailerQuote> = emptyMap(),
    val running: Boolean = false,
    val error: String? = null,
) {
    val cheapest: Retailer? get() = if (running) null else PriceComparison.cheapest(quotes.values)
}

/** Cherche la même liste chez les trois enseignes et garde, pour chacune, un produit par article. */
class CompareViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CompareState())
    val state: StateFlow<CompareState> = _state
    val preferred get() = c.settings.current.preferredRetailer
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            val pending = c.pendingCompare.value
            c.pendingCompare.value = null
            val items = ShoppingListMerger.merge(pending?.items ?: c.shopping.all())
            val label = pending?.label ?: "Liste de courses"
            if (items.isEmpty()) {
                _state.value = CompareState(label = label, error = "Rien à comparer : la liste est vide.")
                return@launch
            }
            _state.value = CompareState(label = label, items = items, running = true)
            // Les WebViews des trois enseignes sont attachées le temps de la comparaison.
            c.comparing.value = Retailer.entries.toSet()
            try {
                PriceComparison.compare(
                    backends = Retailer.entries.map { c.backend(it) },
                    items = items,
                    preferOrganic = c.settings.current.preferOrganic,
                    memory = { c.productMemory(it) },
                    ready = { b -> c.session(b.retailer).let { s -> if (s.ensureSession()) null else s.problem() } },
                    queries = c.settings.current.queryMap(),
                ).collect { q -> _state.update { it.copy(quotes = it.quotes + (q.retailer to q)) } }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                c.comparing.value = emptySet()
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** Reprend le panier proposé par cette enseigne dans l'écran Panier (enseigne active pour ce panier). */
    fun fillWith(r: Retailer) {
        val quote = _state.value.quotes[r] ?: return
        c.pendingQuote.value = PendingQuote(_state.value.label, r, quote.lines)
    }

    override fun onCleared() {
        c.comparing.value = emptySet()
    }
}

private fun euros(v: Double) = String.format(java.util.Locale.FRANCE, "%.2f €", v)

@Composable
fun CompareScreen(
    onBack: () -> Unit,
    onFilled: () -> Unit,
    onLogin: (Retailer) -> Unit,
    onChooseStore: (Retailer) -> Unit,
) {
    val vm = containerViewModel { CompareViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val cheapest = state.cheapest

    ScreenScaffold(title = "Comparer les prix", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoBanner(
                    "⚖️",
                    "${state.label} : ${state.items.size} article(s) cherchés chez Carrefour, E.Leclerc et Auchan, avec tes choix précédents en priorité. " +
                        "Les totaux sont estimés (prix affichés, hors promotions en lot). Ton enseigne préférée reste celle des Réglages.",
                )
            }
            state.error?.let { item { Text("⚠ $it", color = MaterialTheme.colorScheme.error) } }
            items(Retailer.entries.toList(), key = { it.name }) { r ->
                val q = state.quotes[r]
                QuoteCard(
                    retailer = r,
                    quote = q,
                    itemCount = state.items.size,
                    preferred = r == vm.preferred,
                    cheapest = r == cheapest,
                    onFill = { vm.fillWith(r); onFilled() },
                    onLogin = { onLogin(r) },
                    onChooseStore = { onChooseStore(r) },
                )
            }
            if (state.quotes.values.any { it.found > 0 }) {
                item { Text("Détail par article", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                item { DetailHeader() }
                items(state.items.indices.toList(), key = { "item-$it" }) { i -> DetailRow(state.items[i], i, state.quotes) }
            }
            if (!state.running) item {
                OutlinedButton(onClick = vm::start) { Text("Relancer la comparaison") }
            }
        }
    }
}

@Composable
private fun QuoteCard(
    retailer: Retailer,
    quote: RetailerQuote?,
    itemCount: Int,
    preferred: Boolean,
    cheapest: Boolean,
    onFill: () -> Unit,
    onLogin: () -> Unit,
    onChooseStore: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RetailerDot(retailer)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(retailer.label + if (preferred) " ★" else "", style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    quote == null -> "En attente…"
                    quote.problem != null -> quote.problem!!
                    else -> "${quote.priced.size} / $itemCount article(s) avec un prix" + if (quote.missing > 0) " · ${quote.missing} introuvable(s)" else ""
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (quote != null && quote.problem == null && quote.priced.isNotEmpty()) {
                Text(euros(quote.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        if (cheapest) StatChip("💶 Le moins cher", emphasized = true)
        when {
            quote == null -> Unit
            quote.problem != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLogin) { Text("Se connecter") }
                OutlinedButton(onClick = onChooseStore) { Text("Mon drive") }
            }
            !quote.done -> {
                val searched = quote.lines.count { it.status != LineStatus.A_CHERCHER }
                LinearProgressIndicator(
                    progress = { searched.toFloat() / itemCount.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                )
            }
            quote.found > 0 -> Button(onClick = onFill) { Text("Remplir le panier chez ${retailer.label}") }
        }
    }
}

@Composable
private fun DetailHeader() {
    Row(Modifier.fillMaxWidth()) {
        Text("Article", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Retailer.entries.forEach { r ->
            Text(r.label.removePrefix("E."), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        }
    }
}

@Composable
private fun DetailRow(item: ShoppingItem, index: Int, quotes: Map<Retailer, RetailerQuote>) {
    // Prix de la ligne (prix unitaire × quantité proposée) ; le moins cher de la ligne en gras.
    val prices = Retailer.entries.associateWith { r ->
        quotes[r]?.lines?.getOrNull(index)?.let { l -> l.product?.price?.let { it * l.quantity } }
    }
    val best = prices.values.filterNotNull().minOrNull()
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ShoppingListMerger.format(item), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Retailer.entries.forEach { r ->
                val p = prices[r]
                Text(
                    p?.let(::euros) ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (p != null && p == best && prices.values.count { it != null } > 1) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
        val names = Retailer.entries.mapNotNull { r -> quotes[r]?.lines?.getOrNull(index)?.product?.let { "${r.label.removePrefix("E.").take(1)} : ${it.name}" } }
        if (names.isNotEmpty()) {
            Text(names.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

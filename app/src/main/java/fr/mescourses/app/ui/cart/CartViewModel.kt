package fr.mescourses.app.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mescourses.app.AppContainer
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.cart.LineStatus
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, CONNEXION, RECHERCHE, VALIDATION, ENVOI, TERMINE }

data class CartState(
    val retailer: Retailer = Retailer.CARREFOUR,
    val phase: Phase = Phase.IDLE,
    val lines: List<CartLine> = emptyList(),
    val error: String? = null,
    val cartText: String? = null,
)

class CartViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CartState(retailer = c.settings.current.retailer))
    val state: StateFlow<CartState> = _state
    private var job: Job? = null

    fun selectRetailer(r: Retailer) {
        job?.cancel()
        _state.value = CartState(retailer = r)
        viewModelScope.launch { c.settings.update { it.copy(retailer = r) } }
    }

    /** Cherche chaque article non coché de la liste (fusionnée) chez l'enseigne. */
    fun prepare() {
        job?.cancel()
        job = viewModelScope.launch {
            val retailer = _state.value.retailer
            _state.update { it.copy(phase = Phase.CONNEXION, lines = emptyList(), error = null) }
            try {
                val session = c.retailers.session(retailer)
                val items = ShoppingListMerger.merge(c.shopping.all())
                if (items.isEmpty()) {
                    _state.update { it.copy(phase = Phase.IDLE, error = "La liste de courses est vide.") }
                    return@launch
                }
                _state.update { it.copy(phase = Phase.RECHERCHE, lines = items.map { item -> CartLine(item) }) }
                var i = 0
                CartPlanner.propose(session, items, preferOrganic = c.settings.current.preferOrganic).collect { line ->
                    val index = i++
                    _state.update { s -> s.copy(lines = s.lines.toMutableList().also { it[index] = line }) }
                }
                _state.update { it.copy(phase = Phase.VALIDATION) }
            } catch (e: Exception) {
                c.retailers.reset(retailer)
                _state.update { it.copy(phase = Phase.IDLE, error = "Connexion au drive impossible : ${e.message}") }
            }
        }
    }

    fun updateLine(index: Int, transform: (CartLine) -> CartLine) =
        _state.update { s -> s.copy(lines = s.lines.toMutableList().also { it[index] = transform(it[index]) }) }

    /** Envoie les lignes validées au panier, une par une. */
    fun send() {
        job?.cancel()
        job = viewModelScope.launch {
            val retailer = _state.value.retailer
            _state.update { it.copy(phase = Phase.ENVOI, error = null) }
            try {
                val session = c.retailers.session(retailer)
                CartPlanner.fill(session, _state.value.lines).collect { (index, line) -> updateLine(index) { line } }
                val failures = _state.value.lines.count { it.status == LineStatus.ERREUR }
                _state.update { it.copy(phase = Phase.TERMINE, error = if (failures > 0) "$failures article(s) en erreur" else null) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.VALIDATION, error = e.message) }
            }
        }
    }

    fun readCart() = viewModelScope.launch {
        try {
            val text = c.retailers.session(_state.value.retailer).cart()
            _state.update { it.copy(cartText = text) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun dismissCart() = _state.update { it.copy(cartText = null) }

    /** Une fois le panier rempli, coche les articles envoyés dans la liste de courses. */
    fun markSentAsBought() = viewModelScope.launch {
        val sentKeys = _state.value.lines.filter { it.status == LineStatus.AJOUTE }.map { ShoppingListMerger.key(it.item.name) }.toSet()
        c.shopping.all().filter { !it.checked && ShoppingListMerger.key(it.name) in sentKeys }
            .forEach { c.shopping.update(it.copy(checked = true)) }
        _state.update { CartState(retailer = it.retailer) }
    }

    fun isConfigured(r: Retailer) = c.settings.current.mcpUrl(r).isNotBlank()
}

package fr.mescourses.core.cart

import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.retail.Retailer
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** Résultat d'une enseigne dans une comparaison : un produit proposé par article, comme pour préparer le panier. */
data class RetailerQuote(
    val retailer: Retailer,
    val lines: List<CartLine>,
    /** Problème qui empêche de chercher chez cette enseigne (non connecté, pas de drive…). */
    val problem: String? = null,
    val done: Boolean = false,
) {
    /** Articles pour lesquels un produit avec un prix a été trouvé. */
    val priced: List<CartLine> get() = lines.filter { it.included && it.product?.price != null }
    val total: Double get() = Math.round(priced.sumOf { it.product!!.price!! * it.quantity } * 100) / 100.0
    val found: Int get() = lines.count { it.product != null }
    val missing: Int get() = lines.count { it.status == LineStatus.INTROUVABLE || it.status == LineStatus.ERREUR }
}

/**
 * Compare la liste chez plusieurs enseignes. Chaque enseigne a sa propre WebView, on les interroge donc en
 * parallèle, mais chacune au rythme « humain » de [CartPlanner.propose] pour ne pas déclencher les anti-robots.
 */
object PriceComparison {

    fun compare(
        backends: List<RetailerBackend>,
        items: List<ShoppingItem>,
        preferOrganic: Boolean,
        memory: (Retailer) -> ProductMemory?,
        /** Vérifie la session avant de chercher ; renvoie un message d'erreur, ou null si tout va bien. */
        ready: suspend (RetailerBackend) -> String? = { null },
        pauseMs: Long = 600,
    ): Flow<RetailerQuote> = channelFlow {
        coroutineScope {
            backends.map { backend ->
                async {
                    var quote = RetailerQuote(backend.retailer, items.map { CartLine(it) })
                    send(quote)
                    val problem = runCatching { ready(backend) }.getOrElse { it.message ?: it.toString() }
                    if (problem != null) {
                        send(quote.copy(problem = problem, done = true))
                        return@async
                    }
                    var index = 0
                    CartPlanner.propose(backend, items, preferOrganic, memory(backend.retailer), pauseMs).collect { line ->
                        val i = index++
                        quote = quote.copy(lines = quote.lines.toMutableList().also { it[i] = line })
                        send(quote)
                    }
                    send(quote.copy(done = true))
                }
            }.forEach { it.await() }
        }
    }

    /** Enseigne la moins chère parmi celles qui ont trouvé le plus d'articles (un total incomplet n'est pas comparable). */
    fun cheapest(quotes: Collection<RetailerQuote>): Retailer? {
        val usable = quotes.filter { it.done && it.problem == null && it.priced.isNotEmpty() }
        val bestCoverage = usable.maxOfOrNull { it.priced.size } ?: return null
        return usable.filter { it.priced.size == bestCoverage }.minByOrNull { it.total }?.retailer
    }

    /** Clé d'un article, pour aligner les lignes des différentes enseignes. */
    fun key(item: ShoppingItem) = ShoppingListMerger.key(item.name)
}

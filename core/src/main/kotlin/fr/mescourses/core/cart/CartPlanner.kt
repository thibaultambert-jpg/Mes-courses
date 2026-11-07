package fr.mescourses.core.cart

import fr.mescourses.core.mcp.Product
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class LineStatus { A_CHERCHER, PROPOSE, INTROUVABLE, AJOUTE, ERREUR }

/** Une ligne de la liste de courses, avec les produits proposés par l'enseigne. */
data class CartLine(
    val item: ShoppingItem,
    val candidates: List<Product> = emptyList(),
    val selected: Int = 0,
    val quantity: Int = 1,
    /** L'utilisateur décoche une ligne pour ne pas l'envoyer. */
    val included: Boolean = true,
    val status: LineStatus = LineStatus.A_CHERCHER,
    val message: String? = null,
) {
    val product: Product? get() = candidates.getOrNull(selected)
}

/**
 * Prépare puis remplit le panier, ligne par ligne, avec validation humaine entre les deux.
 * Ne passe jamais commande : la validation et le paiement se font chez l'enseigne.
 */
object CartPlanner {

    /** Termes à chercher : on retire les précisions inutiles pour un moteur de recherche drive. */
    fun searchQuery(item: ShoppingItem, preferOrganic: Boolean = false): String {
        val base = item.name
            .replace(Regex("""(?i)\b(frais|fraîche|fraiches|fraîches|bio|émincé|émincée|haché|hachée|en dés|râpé|râpée)\b"""), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { item.name }
        return if (preferOrganic) "$base bio" else base
    }

    /** Classe les produits : ceux dont le nom contient les mots de l'article d'abord, l'ordre de l'enseigne ensuite. */
    fun rank(item: ShoppingItem, products: List<Product>, preferOrganic: Boolean = false): List<Product> {
        val words = ShoppingListMerger.key(item.name).split(' ').filter { it.length > 2 && it != "bio" }
        fun isOrganic(p: Product) = Regex("""\bbio\b""").containsMatchIn(ShoppingListMerger.key(p.name + " " + (p.brand ?: "")))
        return products.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Product>> { (_, p) ->
                    val key = ShoppingListMerger.key(p.name)
                    words.count { it in key }
                }
                    .thenByDescending { (_, p) -> preferOrganic && isOrganic(p) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    fun propose(
        session: RetailerSession,
        items: List<ShoppingItem>,
        preferOrganic: Boolean = false,
        pauseMs: Long = 600,
    ): Flow<CartLine> = flow {
        for (item in items) {
            val line = try {
                var found = session.search(searchQuery(item, preferOrganic))
                // Pas de version bio au drive : on se rabat sur le produit classique.
                if (found.isEmpty() && preferOrganic) found = session.search(searchQuery(item))
                val products = rank(item, found, preferOrganic)
                if (products.isEmpty()) CartLine(item, status = LineStatus.INTROUVABLE, included = false)
                else CartLine(item, products, status = LineStatus.PROPOSE)
            } catch (e: Exception) {
                CartLine(item, status = LineStatus.ERREUR, included = false, message = e.message)
            }
            emit(line)
            delay(pauseMs) // rythme « humain » pour ne pas déclencher les protections anti-robots
        }
    }

    fun fill(session: RetailerSession, lines: List<CartLine>, pauseMs: Long = 1200): Flow<Pair<Int, CartLine>> = flow {
        lines.forEachIndexed { index, line ->
            val product = line.product
            if (!line.included || product == null || line.status == LineStatus.AJOUTE) return@forEachIndexed
            val updated = try {
                val r = session.addToCart(product, line.quantity)
                if (r.isError) line.copy(status = LineStatus.ERREUR, message = r.text.take(200))
                else line.copy(status = LineStatus.AJOUTE, message = null)
            } catch (e: Exception) {
                line.copy(status = LineStatus.ERREUR, message = e.message)
            }
            emit(index to updated)
            delay(pauseMs)
        }
    }
}

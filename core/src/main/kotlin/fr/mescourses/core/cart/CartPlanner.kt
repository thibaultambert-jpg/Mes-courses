package fr.mescourses.core.cart

import fr.mescourses.core.carrefour.Product
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
    /** Produit repris de la mémoire des choix (pas de recherche faite). */
    val remembered: Boolean = false,
    /** Remarque affichée (ex. « Remplace X, indisponible au drive »). */
    val note: String? = null,
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
            .let { if (ShoppingListMerger.isEgg(it)) "oeufs x12" else it }
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

    /**
     * Propose un produit par article. Un produit déjà choisi pour cet ingrédient (mémoire) est présélectionné
     * sans recherche ; sinon on cherche (bio d'abord si demandé).
     */
    fun propose(
        backend: RetailerBackend,
        items: List<ShoppingItem>,
        preferOrganic: Boolean = false,
        memory: ProductMemory? = null,
        pauseMs: Long = 600,
    ): Flow<CartLine> = flow {
        val remembered = items.associateWith { memory?.get(ShoppingListMerger.key(it.name)) }
        // Les choix habituels peuvent être en rupture : vérification groupée avant de les proposer.
        val availability = remembered.values.filterNotNull().map { it.ean }.takeIf { it.isNotEmpty() }
            ?.let { runCatching { backend.availability(it) }.getOrDefault(emptyMap()) }.orEmpty()
        for (item in items) {
            val known = remembered[item]
            if (known != null && availability[known.ean] != false) {
                emit(CartLine(item, listOf(known), status = LineStatus.PROPOSE, remembered = true))
                continue
            }
            if (known != null) {
                // Choix habituel indisponible : on cherche un remplaçant disponible.
                val line = searchLine(backend, item, preferOrganic)
                emit(line.copy(candidates = line.candidates.filter { it.ean != known.ean }, note = "Remplace « ${known.name} », indisponible au drive"))
                delay(pauseMs)
                continue
            }
            emit(searchLine(backend, item, preferOrganic))
            delay(pauseMs) // rythme « humain » pour ne pas déclencher les protections anti-robots
        }
    }

    /** Recherche des produits pour une ligne (aussi utilisée pour « autres choix » d'un produit mémorisé). */
    suspend fun searchLine(backend: RetailerBackend, item: ShoppingItem, preferOrganic: Boolean): CartLine = try {
        var found = backend.searchWithUnavailable(searchQuery(item, preferOrganic))
        // Pas de version bio disponible au drive : on se rabat sur le produit classique.
        if (found.none { it.available } && preferOrganic) found = backend.searchWithUnavailable(searchQuery(item))
        val ranked = rank(item, found, preferOrganic)
        val available = ranked.filter { it.available }
        // Les produits en rupture restent visibles dans « autres choix » (marqués), jamais présélectionnés.
        val products = available + ranked.filterNot { it.available }
        val skipped = ranked.firstOrNull()?.takeIf { !it.available }
        when {
            available.isEmpty() && ranked.isNotEmpty() ->
                CartLine(item, products, status = LineStatus.INTROUVABLE, included = false, note = "Tous les produits trouvés sont indisponibles au drive")
            available.isEmpty() -> CartLine(item, status = LineStatus.INTROUVABLE, included = false)
            else -> CartLine(item, products, status = LineStatus.PROPOSE, note = skipped?.let { "« ${it.name} » indisponible : remplacé par le suivant" })
        }
    } catch (e: Exception) {
        CartLine(item, status = LineStatus.ERREUR, included = false, message = e.message)
    }

    /** Envoie les lignes validées et mémorise les choix confirmés. */
    fun fill(
        backend: RetailerBackend,
        lines: List<CartLine>,
        memory: ProductMemory? = null,
        pauseMs: Long = 1200,
    ): Flow<Pair<Int, CartLine>> = flow {
        lines.forEachIndexed { index, line ->
            val product = line.product
            if (!line.included || product == null || line.status == LineStatus.AJOUTE) return@forEachIndexed
            val updated = try {
                backend.addToCart(product, line.quantity)
                memory?.remember(ShoppingListMerger.key(line.item.name), product)
                line.copy(status = LineStatus.AJOUTE, message = null)
            } catch (e: Exception) {
                line.copy(status = LineStatus.ERREUR, message = e.message)
            }
            emit(index to updated)
            delay(pauseMs)
        }
    }
}

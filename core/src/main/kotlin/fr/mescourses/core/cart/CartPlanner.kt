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
    /** Recherche envoyée au drive pour cette ligne (modifiable dans la feuille de choix). */
    val query: String? = null,
    /** Les `relevantCount` premiers candidats contiennent un mot de la recherche ; les suivants sont « sans rapport ». */
    val relevantCount: Int = candidates.size,
) {
    val product: Product? get() = candidates.getOrNull(selected)
}

/**
 * Prépare puis remplit le panier, ligne par ligne, avec validation humaine entre les deux.
 * Ne passe jamais commande : la validation et le paiement se font chez l'enseigne.
 */
object CartPlanner {

    /** Termes à chercher : le nom simplifié, sans « bio » (le bio est choisi parmi les résultats, voir [rank]). */
    fun searchQuery(item: ShoppingItem): String =
        fr.mescourses.core.ingredients.FoodName.clean(item.name)
            .replace(Regex("""(?i)\b(frais|fraîche|fraiches|fraîches|bio|émincé|émincée|haché|hachée|en dés|râpé|râpée)\b"""), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { item.name }
            .let { if (ShoppingListMerger.isEgg(it)) "oeufs x12" else it }

    /** Recherche à utiliser : celle corrigée par l'utilisateur pour cet ingrédient, sinon la recherche calculée. */
    fun queryFor(item: ShoppingItem, custom: Map<String, String> = emptyMap()): String =
        custom[ShoppingListMerger.key(item.name)]?.takeIf { it.isNotBlank() } ?: searchQuery(item)

    private val stopWords = setOf("les", "des", "aux", "avec", "pour", "sans", "une", "the")

    /** Mots qui comptent dans une recherche (au moins 3 lettres, sans accents, au singulier). */
    fun words(query: String): List<String> =
        ShoppingListMerger.key(query).split(' ').filter { it.length >= 3 && it !in stopWords && it != "bio" }

    /** Le produit contient au moins un mot de la recherche (« lardon » ≠ « Lait corps Nivea »). */
    fun isRelevant(query: String, p: Product): Boolean {
        val w = words(query)
        if (w.isEmpty()) return true
        val k = " " + ShoppingListMerger.key(p.name + " " + (p.brand ?: "")) + " "
        return w.any { k.contains(" $it") }
    }

    /** Recherche plus large : sans le dernier mot (« coulis de tomate jaune » → « coulis de tomate »). */
    fun widen(query: String): String? {
        val parts = query.trim().split(Regex("\\s+")).toMutableList()
        if (parts.size < 2) return null
        parts.removeAt(parts.lastIndex)
        while (parts.size > 1 && parts.last().lowercase().let { it in setOf("de", "du", "des", "d'", "à", "a", "au", "aux", "en", "et") }) parts.removeAt(parts.lastIndex)
        return parts.joinToString(" ")
    }

    private val freshWords = listOf("frais", "fraiche", "botte", "bouquet", "barquette", "filet", "vrac", "piece", "kg")
    private val processedWords = listOf(
        "surgele", "seche", "deshydrate", "lyophilise", "poudre", "moulu", "conserve", "bocal", "emince", "cuisine",
        "poelee", "puree", "soupe", "grille", "marine", "appertise", "cube", "confit", "pickle", "frit", "chips", "tapenade",
        "semoule", "persillade", "sauce", "aromate", "pate", "creme", "saveur", "aromatise", "huile", "pot", "flocon",
        "condiment", "vinaigrette", "pesto", "puree", "chip",
    ).map(ShoppingListMerger::key).distinct()

    /** Fruits, légumes et herbes : le frais d'abord (1), le transformé (surgelé, séché, conserve…) en dernier (-1). */
    fun freshness(item: ShoppingItem, p: Product): Int {
        if (fr.mescourses.core.shopping.Aisle.of(item.name) != fr.mescourses.core.shopping.Aisle.FRUITS_LEGUMES) return 0
        val k = " " + ShoppingListMerger.key(p.name) + " "
        // Comparaison en début de mot (« chips » → « chip », « surgelés » → « surgele »).
        return when {
            processedWords.any { k.contains(" $it") } -> -1
            freshWords.any { k.contains(" $it") } -> 1
            else -> 0
        }
    }

    /**
     * Classe les produits : pertinents d'abord, puis frais (fruits, légumes, herbes), puis bio si demandé,
     * puis le nombre de mots de l'article retrouvés, et enfin l'ordre de l'enseigne.
     */
    fun rank(item: ShoppingItem, products: List<Product>, preferOrganic: Boolean = false, query: String = searchQuery(item)): List<Product> {
        val words = (words(item.name) + words(query)).distinct()
        return products.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Product>> { (_, p) -> isRelevant(query, p) }
                    // Transformé (surgelé, chips, semoule…) en dernier, puis le bio, puis le frais.
                    .thenByDescending { (_, p) -> freshness(item, p) >= 0 }
                    .thenByDescending { (_, p) -> preferOrganic && p.organic }
                    .thenByDescending { (_, p) -> freshness(item, p) }
                    .thenByDescending { (_, p) ->
                        val key = " " + ShoppingListMerger.key(p.name) + " "
                        words.count { key.contains(" $it") }
                    }
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
        /** Recherches corrigées par l'utilisateur (clé de l'ingrédient → recherche). */
        queries: Map<String, String> = emptyMap(),
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
                val line = searchLine(backend, item, preferOrganic, queryFor(item, queries))
                emit(line.copy(candidates = line.candidates.filter { it.ean != known.ean }, note = "Remplace « ${known.name} », indisponible au drive"))
                delay(pauseMs)
                continue
            }
            emit(searchLine(backend, item, preferOrganic, queryFor(item, queries)))
            delay(pauseMs) // rythme « humain » pour ne pas déclencher les protections anti-robots
        }
    }

    /**
     * Recherche des produits pour une ligne. Toujours la recherche classique : le bio passe ensuite en tête parmi
     * les produits pertinents, et à défaut on garde le meilleur produit classique. Tous les produits reçus sont
     * gardés (aucune requête de plus) ; ceux sans rapport avec la recherche ne sont jamais présélectionnés.
     */
    suspend fun searchLine(backend: RetailerBackend, item: ShoppingItem, preferOrganic: Boolean, query: String = searchQuery(item)): CartLine = try {
        val found = backend.searchWithUnavailable(query, Int.MAX_VALUE)
        val ranked = rank(item, found, preferOrganic, query)
        val relevant = ranked.filter { isRelevant(query, it) }
        val others = ranked.filterNot { isRelevant(query, it) }
        val available = relevant.filter { it.available }
        // Les produits en rupture restent visibles (marqués), jamais présélectionnés.
        val products = available + relevant.filterNot { it.available } + others
        val skipped = relevant.firstOrNull()?.takeIf { !it.available }
        val base = CartLine(item, products, query = query, relevantCount = relevant.size)
        when {
            available.isEmpty() && relevant.isNotEmpty() ->
                base.copy(status = LineStatus.INTROUVABLE, included = false, note = "Tous les produits trouvés sont indisponibles au drive")
            available.isEmpty() && others.isNotEmpty() ->
                base.copy(status = LineStatus.INTROUVABLE, included = false, note = "Aucun produit ne correspond à « $query » : modifie la recherche")
            available.isEmpty() -> base.copy(status = LineStatus.INTROUVABLE, included = false)
            else -> base.copy(
                status = LineStatus.PROPOSE,
                note = skipped?.let { "« ${it.name} » indisponible : remplacé par le suivant" }
                    ?: if (preferOrganic && available.none { it.organic }) "Pas de version bio au drive" else null,
            )
        }
    } catch (e: Exception) {
        CartLine(item, status = LineStatus.ERREUR, included = false, message = e.message, query = query)
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

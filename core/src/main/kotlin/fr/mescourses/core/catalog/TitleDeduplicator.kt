package fr.mescourses.core.catalog

import fr.mescourses.core.recipes.RecipeRating
import kotlin.math.ceil

/**
 * Doublons par titre à grande échelle (lot 2.1b) : deux fiches sont « la même recette » quand les ensembles de mots
 * utiles de leurs titres ([CatalogQuery.words]) ont un indice de Jaccard ≥ un seuil.
 *
 * Comparer toutes les paires est impossible sur ~200 000 fiches ; on utilise le filtre de préfixe d'AllPairs : les mots
 * de chaque titre sont rangés du plus rare au plus fréquent, et deux titres assez proches partagent forcément un mot
 * parmi les `|w| − ⌈t·|w|⌉ + 1` premiers de chacun. Un index inversé sur ces préfixes donne les paires candidates,
 * vérifiées ensuite exactement ; les groupes sont les composantes connexes (union-find). Les titres aux mots
 * identiques sont regroupés d'emblée, sans passer par l'index. Résultat déterministe.
 */
object TitleDeduplicator {
    /** Groupes trouvés et nombre de mots ignorés parce que leur liste dans l'index dépassait `maxPostings`. */
    data class Analysis(val groups: List<IntArray>, val skippedWords: Int)

    /** Groupes d'indices (taille ≥ 2) de fiches dont les titres ont un Jaccard ≥ threshold sur leurs mots. */
    fun groups(entries: List<CatalogEntry>, threshold: Double = 0.75, maxPostings: Int = 5_000, crossSites: Boolean = true): List<IntArray> =
        analyze(entries, threshold, maxPostings, crossSites).groups

    /** Comme [groups], avec le nombre de mots ignorés (avertissement aussi écrit sur la sortie d'erreur). */
    fun analyze(entries: List<CatalogEntry>, threshold: Double = 0.75, maxPostings: Int = 5_000, crossSites: Boolean = true): Analysis =
        graph(entries, threshold, maxPostings, crossSites).let { Analysis(it.groups, it.skippedWords) }

    /**
     * Garde les meilleures fiches de chaque groupe : max par (RecipeRating.score, ratingCount, ingredients.size, puis url
     * croissante) ; ordre d'entrée conservé.
     *
     * Les groupes étant des composantes connexes, ils s'étendent par chaînage (A ~ B ~ C sans A ~ C) : sur la base
     * réelle, une composante réunit près de 20 000 titres sans rapport entre eux. Une fiche n'est donc retirée que si
     * elle est directement semblable (Jaccard ≥ threshold) à une fiche gardée meilleure qu'elle : dans un groupe, les
     * fiches sont parcourues de la meilleure à la moins bonne et chacune est gardée si aucune fiche déjà gardée ne lui
     * ressemble. Un groupe dont les titres se ressemblent tous deux à deux garde exactement une fiche.
     */
    fun dedupe(entries: List<CatalogEntry>, threshold: Double = 0.75, maxPostings: Int = 5_000, crossSites: Boolean = true): List<CatalogEntry> {
        val graph = graph(entries, threshold, maxPostings, crossSites)
        val removed = BooleanArray(entries.size)
        val keptInClass = BooleanArray(entries.size)
        val byQuality = Comparator<Int> { a, b -> if (better(entries[a], entries[b])) -1 else if (better(entries[b], entries[a])) 1 else 0 }
        for (group in graph.groups) {
            for (i in group.sortedWith(byQuality)) {
                val c = graph.classOf[i]
                if (keptInClass[c] || graph.anyNeighbour(c) { keptInClass[it] }) removed[i] = true else keptInClass[c] = true
            }
        }
        return entries.filterIndexed { i, _ -> !removed[i] }
    }

    /** [a] doit être gardée plutôt que [b]. */
    private fun better(a: CatalogEntry, b: CatalogEntry): Boolean {
        val sa = RecipeRating.score(a.rating, a.ratingCount)
        val sb = RecipeRating.score(b.rating, b.ratingCount)
        if (sa != sb) return sa > sb
        val ca = a.ratingCount ?: 0
        val cb = b.ratingCount ?: 0
        if (ca != cb) return ca > cb
        if (a.ingredients.size != b.ingredients.size) return a.ingredients.size > b.ingredients.size
        return a.url < b.url
    }

    internal fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val inter = if (a.size <= b.size) a.count { it in b } else b.count { it in a }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /**
     * Graphe de similarité : [classOf] donne pour chaque fiche le représentant des titres aux mots identiques (-1 si le
     * titre n'a aucun mot) ; les arêtes relient des représentants ; [groups] sont les composantes connexes.
     */
    private class Graph(val classOf: IntArray, private val adjacency: Array<IntArrayList?>, val groups: List<IntArray>, val skippedWords: Int) {
        fun anyNeighbour(rep: Int, predicate: (Int) -> Boolean): Boolean {
            val l = adjacency[rep] ?: return false
            for (k in 0 until l.size) if (predicate(l[k])) return true
            return false
        }
    }

    private fun graph(entries: List<CatalogEntry>, threshold: Double, maxPostings: Int, crossSites: Boolean): Graph {
        require(threshold > 0.0 && threshold <= 1.0) { "seuil hors de ]0, 1] : $threshold" }
        val n = entries.size
        val wordSets = entries.map { CatalogQuery.words(it.title) }

        // Fréquence globale, puis rang (fréquence croissante, alphabétique) : le rang sert d'identifiant de mot.
        val freq = HashMap<String, Int>()
        for (ws in wordSets) for (w in ws) freq.merge(w, 1, Int::plus)
        val rank = HashMap<String, Int>(freq.size * 2)
        freq.keys.sortedWith(compareBy<String>({ freq.getValue(it) }, { it })).forEachIndexed { r, w -> rank[w] = r }
        val records = Array(n) { i -> IntArray(wordSets[i].size) { rank.getValue(wordSets[i][it]) }.also { it.sort() } }

        val uf = UnionFind(n)
        val classOf = IntArray(n) { -1 }
        val adjacency = arrayOfNulls<IntArrayList>(n)
        // Mots identiques (et même site si !crossSites) : regroupés directement ; un seul représentant passe par l'index.
        val reps = ArrayList<Int>()
        val byKey = HashMap<Pair<List<Int>, String?>, Int>()
        for (i in 0 until n) {
            if (records[i].isEmpty()) continue
            val key = records[i].asList() to (if (crossSites) null else entries[i].siteId)
            val first = byKey.putIfAbsent(key, i)
            if (first == null) reps += i else uf.union(first, i)
            classOf[i] = first ?: i
        }

        fun prefixLength(size: Int) = size - ceil(threshold * size - 1e-9).toInt() + 1

        // Taille de chaque liste de l'index : les mots trop fréquents sont ignorés.
        val postingSize = IntArray(rank.size)
        for (i in reps) { val r = records[i]; for (k in 0 until prefixLength(r.size)) postingSize[r[k]]++ }
        val skipped = postingSize.count { it > maxPostings }

        // Index construit au fil de l'eau : chaque fiche est comparée aux fiches déjà indexées sous un mot de son préfixe.
        val postings = arrayOfNulls<IntArrayList>(rank.size)
        val seen = IntArray(n) { -1 }
        for (i in reps) {
            val r = records[i]
            for (k in 0 until prefixLength(r.size)) {
                val w = r[k]
                if (postingSize[w] > maxPostings) continue
                val list = postings[w] ?: IntArrayList().also { postings[w] = it }
                for (idx in 0 until list.size) {
                    val j = list[idx]
                    if (seen[j] == i) continue
                    seen[j] = i
                    if (!crossSites && entries[j].siteId != entries[i].siteId) continue
                    if (similar(r, records[j], threshold)) {
                        uf.union(i, j)
                        (adjacency[i] ?: IntArrayList().also { adjacency[i] = it }).add(j)
                        (adjacency[j] ?: IntArrayList().also { adjacency[j] = it }).add(i)
                    }
                }
                list.add(i)
            }
        }

        val members = LinkedHashMap<Int, IntArrayList>()
        for (i in 0 until n) if (records[i].isNotEmpty()) members.getOrPut(uf.find(i)) { IntArrayList() }.add(i)
        val groups = members.values.filter { it.size >= 2 }.map { it.toArray() }.sortedBy { it[0] }
        if (skipped > 0) System.err.println("TitleDeduplicator : $skipped mot(s) ignoré(s) (plus de $maxPostings fiches dans l'index)")
        return Graph(classOf, adjacency, groups, skipped)
    }

    /** Jaccard ≥ t sur deux ensembles triés de rangs, sans allocation. */
    private fun similar(a: IntArray, b: IntArray, t: Double): Boolean {
        val small = minOf(a.size, b.size)
        val large = maxOf(a.size, b.size)
        if (small < t * large - 1e-9) return false
        var x = 0
        var y = 0
        var inter = 0
        while (x < a.size && y < b.size) {
            when {
                a[x] == b[y] -> { inter++; x++; y++ }
                a[x] < b[y] -> x++
                else -> y++
            }
        }
        return inter.toDouble() / (a.size + b.size - inter) >= t - 1e-12
    }

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        /** La racine est toujours le plus petit indice : groupes indépendants de l'ordre des unions. */
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra < rb) parent[rb] = ra else if (rb < ra) parent[ra] = rb
        }
    }

    private class IntArrayList {
        private var data = IntArray(4)
        var size = 0
            private set
        operator fun get(i: Int) = data[i]
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray(): IntArray = data.copyOf(size)
    }
}

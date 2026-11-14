package fr.mescourses.core.ingredients

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Graphe des accords entre ingrédients : chaque paire de clés canoniques porte un poids (plus il est fort, mieux
 * les deux ingrédients vont ensemble). Les paires sont symétriques : (a, b) vaut (b, a).
 */
class PairingGraph(pairs: List<Pairing>) {
    /** Un accord entre deux clés d'ingrédients et son poids. */
    @Serializable
    data class Pairing(val a: String, val b: String, val w: Double)

    @Serializable
    private data class File(val version: Int = 1, val pairs: List<Pairing> = emptyList())

    /** Poids par clé puis par clé voisine (les deux sens sont enregistrés). */
    private val weights: Map<String, Map<String, Double>> = buildMap<String, MutableMap<String, Double>> {
        for (p in pairs) {
            if (p.a == p.b) continue
            getOrPut(p.a) { HashMap() }[p.b] = p.w
            getOrPut(p.b) { HashMap() }[p.a] = p.w
        }
    }

    /** Moyenne des poids des paires présentes parmi [keys] ; 0.0 s'il y a moins de deux clés ou aucune paire. */
    fun affinityScore(keys: Collection<String>): Double {
        val distinct = keys.distinct()
        if (distinct.size < 2) return 0.0
        var sum = 0.0
        var count = 0
        for (i in distinct.indices) for (j in i + 1 until distinct.size) {
            val w = weights[distinct[i]]?.get(distinct[j]) ?: continue
            sum += w
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Les [n] meilleurs accords de [key], par poids décroissant. */
    fun bestPairings(key: String, n: Int = 5): List<Pair<String, Double>> =
        weights[key].orEmpty().entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    companion object {
        private const val RESOURCE = "/fr/mescourses/core/ingredients/pairings.json"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val EMPTY = PairingGraph(emptyList())

        private val loaded: PairingGraph by lazy {
            runCatching {
                val text = PairingGraph::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
                if (text.isNullOrBlank()) EMPTY
                else json.decodeFromString(File.serializer(), text).pairs.takeIf { it.isNotEmpty() }?.let(::PairingGraph) ?: EMPTY
            }.getOrDefault(EMPTY)
        }

        /** Graphe de la ressource `pairings.json`, lu une fois ; [EMPTY] si elle est absente, vide ou illisible. */
        fun load(): PairingGraph = loaded
    }
}

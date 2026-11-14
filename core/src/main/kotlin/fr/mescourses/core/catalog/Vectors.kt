package fr.mescourses.core.catalog

/**
 * Calcul des vecteurs sémantiques (256 dimensions, INT8) d'une saisie ou de fiches du catalogue. Implémenté côté
 * appli (modèle embarqué) et côté CI ; null quand le modèle n'est pas disponible.
 */
interface TextEmbedder {
    /** Le modèle est prêt à servir. */
    suspend fun available(): Boolean

    /** Vecteur d'une saisie de recherche (texte brut, mis en forme par [EmbeddingText.query]) ; null si indisponible. */
    suspend fun embedQuery(text: String): ByteArray?

    /** Vecteurs de textes de fiches (mis en forme par [EmbeddingText.passage]), dans l'ordre ; null si indisponible. */
    suspend fun embedPassages(texts: List<String>): List<ByteArray>?
}

/**
 * Textes donnés au modèle d'embeddings. Changer leur forme oblige à recalculer tous les vecteurs du catalogue :
 * augmenter alors [VERSION].
 */
object EmbeddingText {
    const val VERSION = 1

    fun passage(e: CatalogEntry): String = "task: search result | text: ${e.title}. Ingrédients : ${e.keys.joinToString(", ")}"

    fun query(text: String): String = "task: search query | text: ${text.trim()}"
}

// --- Vecteurs INT8 et fusion des classements ---

/**
 * Vecteurs INT8 du catalogue. Contrat partagé avec `tools/catalog/embed.py` (fixture `embedder-golden.json`) :
 * [DIM] dimensions, vecteur flottant normalisé L2, `q = clamp(rint(x / SCALE × 127), −127, 127)` calculé en float32
 * (arrondi au pair le plus proche, comme `numpy.rint`), octets signés encodés en Base64 standard (344 caractères).
 *
 * Avec des vecteurs normalisés, le produit scalaire de deux vecteurs quantifiés vaut ≈ cos × (127 / SCALE)² : il
 * suffit pour classer (même ordre que le cosinus, à l'erreur de quantification près).
 */
object Int8Vectors {
    const val DIM = 256
    const val SCALE = 0.25f

    /** Écart de norme au-delà duquel [quantize] renormalise. */
    private const val NORM_TOLERANCE = 1e-3

    /** [v] est normalement déjà normalisé (sortie du modèle) ; sinon il est normalisé ici (vecteur nul : tout à 0). */
    fun quantize(v: FloatArray): ByteArray {
        var sq = 0.0
        for (x in v) sq += x.toDouble() * x
        val norm = kotlin.math.sqrt(sq)
        val factor = if (norm > 0 && kotlin.math.abs(norm - 1.0) > NORM_TOLERANCE) (1.0 / norm).toFloat() else 1f
        val out = ByteArray(v.size)
        for (i in v.indices) {
            val x = if (factor == 1f) v[i] else v[i] * factor
            val q = Math.rint((x / SCALE * 127f).toDouble())
            out[i] = q.coerceIn(-127.0, 127.0).toInt().toByte()
        }
        return out
    }

    /**
     * Produit scalaire de `a[aOffset until aOffset + b.size]` et de [b] (la requête). Boucle déroulée par 8,
     * accumulateur entier : |résultat| ≤ 127² × [DIM], sans débordement.
     */
    fun dot(a: ByteArray, aOffset: Int, b: ByteArray): Int {
        val n = b.size
        var s0 = 0; var s1 = 0; var s2 = 0; var s3 = 0
        var i = 0
        val end8 = n - (n and 7)
        while (i < end8) {
            val j = aOffset + i
            s0 += a[j] * b[i] + a[j + 4] * b[i + 4]
            s1 += a[j + 1] * b[i + 1] + a[j + 5] * b[i + 5]
            s2 += a[j + 2] * b[i + 2] + a[j + 6] * b[i + 6]
            s3 += a[j + 3] * b[i + 3] + a[j + 7] * b[i + 7]
            i += 8
        }
        while (i < n) { s0 += a[aOffset + i] * b[i]; i++ }
        return s0 + s1 + s2 + s3
    }

    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** null si le texte n'est pas du Base64 valide ou ne donne pas [DIM] octets. */
    fun decode(base64: String): ByteArray? {
        val bytes = try {
            java.util.Base64.getDecoder().decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.size == DIM }
    }
}

/**
 * Index en mémoire des vecteurs du catalogue, pour une recherche exhaustive des plus proches voisins (≈ 200 k fiches
 * × 256 octets ≈ 50 Mo, parcourus en quelques dizaines de millisecondes).
 *
 * [ids] : identifiants triés par ordre croissant ; `data` : leurs vecteurs bout à bout (`ids.size × dim` octets),
 * dans le même ordre.
 */
class Int8VectorIndex(val ids: LongArray, private val data: ByteArray, val dim: Int = Int8Vectors.DIM) {
    data class Hit(val id: Long, val score: Int)

    init {
        require(data.size == ids.size * dim) { "data : ${data.size} octets pour ${ids.size} vecteurs de $dim" }
        for (i in 1 until ids.size) require(ids[i - 1] < ids[i]) { "ids non triés ou en double à l'indice $i" }
    }

    val size: Int get() = ids.size

    /** Positions de l'index dont l'id figure dans [allowedSortedIds] (triés croissants) : fusion à deux pointeurs. */
    fun mask(allowedSortedIds: LongArray): BooleanArray {
        val out = BooleanArray(ids.size)
        var i = 0
        var j = 0
        while (i < ids.size && j < allowedSortedIds.size) {
            val a = ids[i]
            val b = allowedSortedIds[j]
            when {
                a < b -> i++
                a > b -> j++
                else -> { out[i] = true; i++; j++ }
            }
        }
        return out
    }

    /**
     * Les [k] vecteurs de plus grand produit scalaire avec [query], du meilleur au moins bon ; à score égal, le plus
     * petit id d'abord. [allowed] (voir [mask]) : seules ces positions concourent.
     */
    fun topK(query: ByteArray, k: Int, allowed: BooleanArray? = null): List<Hit> {
        require(query.size == dim) { "requête de ${query.size} dimensions, index de $dim" }
        require(allowed == null || allowed.size == ids.size) { "masque de ${allowed?.size} positions pour ${ids.size} vecteurs" }
        if (k <= 0 || ids.isEmpty()) return emptyList()
        // Tas borné : en tête, le moins bon des retenus (plus petit score, puis plus grand id).
        val worstFirst = Comparator<Hit> { a, b -> if (a.score != b.score) a.score.compareTo(b.score) else b.id.compareTo(a.id) }
        val heap = java.util.PriorityQueue(minOf(k, ids.size), worstFirst)
        var offset = 0
        for (i in ids.indices) {
            if (allowed == null || allowed[i]) {
                val score = Int8Vectors.dot(data, offset, query)
                if (heap.size < k) {
                    heap.add(Hit(ids[i], score))
                } else if (score > heap.peek().score) {
                    // Les ids croissent : à score égal, le nouveau venu (id plus grand) ne remplace jamais.
                    heap.poll()
                    heap.add(Hit(ids[i], score))
                }
            }
            offset += dim
        }
        return heap.sortedWith(worstFirst.reversed())
    }

    companion object {
        /** Index des couples (id, vecteur), triés ici par id. Les vecteurs font tous [Int8Vectors.DIM] octets. */
        fun build(rows: List<Pair<Long, ByteArray>>): Int8VectorIndex {
            val sorted = rows.sortedBy { it.first }
            val dim = Int8Vectors.DIM
            val ids = LongArray(sorted.size)
            val data = ByteArray(sorted.size * dim)
            sorted.forEachIndexed { i, (id, v) ->
                require(v.size == dim) { "vecteur de ${v.size} octets pour l'id $id" }
                ids[i] = id
                System.arraycopy(v, 0, data, i * dim, dim)
            }
            return Int8VectorIndex(ids, data, dim)
        }
    }
}

/**
 * Fusion de classements (Reciprocal Rank Fusion), par exemple mots (FTS) et vecteurs : chaque id reçoit
 * Σ 1 / (k + rang), rang compté à partir de 1 dans chaque liste où il apparaît. Tri par score décroissant ; à score
 * égal, le meilleur rang obtenu dans une liste d'abord, puis l'ordre de première apparition.
 */
object ReciprocalRankFusion {
    fun fuse(rankings: List<List<Long>>, k: Int = 60): List<Long> {
        class Acc(val first: Int) { var score = 0.0; var bestRank = Int.MAX_VALUE }
        val acc = LinkedHashMap<Long, Acc>()
        for (list in rankings) {
            val seen = HashSet<Long>()
            list.forEachIndexed { i, id ->
                if (seen.add(id)) {
                    val a = acc.getOrPut(id) { Acc(acc.size) }
                    val rank = i + 1
                    a.score += 1.0 / (k + rank)
                    if (rank < a.bestRank) a.bestRank = rank
                }
            }
        }
        return acc.entries.sortedWith(
            compareByDescending<Map.Entry<Long, Acc>> { it.value.score }.thenBy { it.value.bestRank }.thenBy { it.value.first },
        ).map { it.key }
    }
}

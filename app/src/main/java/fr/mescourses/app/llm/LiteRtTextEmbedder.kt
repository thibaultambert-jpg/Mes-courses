package fr.mescourses.app.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import fr.mescourses.core.catalog.EmbeddingText
import fr.mescourses.core.catalog.Int8Vectors
import fr.mescourses.core.catalog.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vecteurs sémantiques calculés sur l'appareil par EmbeddingGemma (LiteRT-LM, [EmbeddingEngine]) : CPU, sortie
 * normalisée de [Int8Vectors.DIM] dimensions, quantifiée en INT8 (même contrat que `tools/catalog/embed.py`).
 *
 * Le moteur est chargé à la première demande et gardé en mémoire jusqu'à [release] (appelé quand le système manque
 * de mémoire). Un seul calcul à la fois. Si le chargement échoue, on ne réessaie pas avant [release].
 */
class LiteRtTextEmbedder(
    context: Context,
    private val modelFile: File = File(context.filesDir, "embedder/embeddinggemma-2-740m.litertlm"),
) : TextEmbedder {

    private val cacheDir: String = context.cacheDir.path
    private val mutex = Mutex()

    /** Lu hors verrou par [loaded] ; écrit uniquement sous [mutex]. */
    @Volatile private var engine: EmbeddingEngine? = null
    /** Dernier chargement en échec : pas de nouvel essai avant [release]. Sous [mutex]. */
    private var loadFailed = false
    /** [release] demandé pendant un calcul : le détenteur du verrou libère le moteur en sortant. */
    @Volatile private var releasePending = false
    /** Incrémenté à chaque libération : une vectorisation de fiches interrompue par une libération est abandonnée. */
    @Volatile private var generation = 0

    private val options = EmbeddingOptions(normalize = true, outputSize = Int8Vectors.DIM)

    /** Le moteur est chargé en mémoire. */
    val loaded: Boolean get() = engine != null

    /** Fichier du modèle présent et non vide (sans le charger). */
    override suspend fun available(): Boolean = withContext(Dispatchers.IO) { modelFile.isFile && modelFile.length() > 0L }

    override suspend fun embedQuery(text: String): ByteArray? = withEngine { e ->
        val start = SystemClock.elapsedRealtime()
        try {
            val v = e.computeEmbedding(listOf(InputData.Text(EmbeddingText.query(text))), options).embedding
            if (v.size != Int8Vectors.DIM) {
                Log.w(TAG, "Vecteur de saisie de taille ${v.size} (attendu ${Int8Vectors.DIM})")
                null
            } else {
                Int8Vectors.quantize(v).also { Log.d(TAG, "Saisie vectorisée en ${SystemClock.elapsedRealtime() - start} ms") }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Échec du calcul du vecteur de la saisie", t)
            null
        }
    }

    /**
     * Vecteurs de [texts], **déjà mis en forme par l'appelant** ([EmbeddingText.passage]) : aucun préfixe n'est ajouté
     * ici. Calculés par lots de [BATCH] ; null si le moteur est indisponible, si un lot échoue ou si le moteur est
     * libéré en cours de route. Le verrou est rendu entre deux lots pour qu'une recherche puisse passer.
     */
    override suspend fun embedPassages(texts: List<String>): List<ByteArray>? {
        if (texts.isEmpty()) return if (available()) emptyList() else null
        val start = SystemClock.elapsedRealtime()
        val gen = generation
        val out = ArrayList<ByteArray>(texts.size)
        for (chunk in texts.chunked(BATCH)) {
            val vectors = withEngine<List<ByteArray>>(proceed = { generation == gen }) { e ->
                try {
                    val responses = e.computeEmbeddingBatch(chunk.map { listOf(InputData.Text(it)) }, options)
                    if (responses.size != chunk.size) {
                        Log.w(TAG, "Lot de ${chunk.size} fiches : ${responses.size} vecteurs reçus")
                        return@withEngine null
                    }
                    responses.map { r ->
                        val v = r.embedding
                        if (v.size != Int8Vectors.DIM) {
                            Log.w(TAG, "Vecteur de fiche de taille ${v.size} (attendu ${Int8Vectors.DIM})")
                            return@withEngine null
                        }
                        Int8Vectors.quantize(v)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Échec du calcul d'un lot de ${chunk.size} fiches", t)
                    null
                }
            }
            if (vectors == null) {
                if (generation != gen) Log.i(TAG, "Vectorisation des fiches interrompue (moteur libéré)")
                return null
            }
            out += vectors
        }
        Log.d(TAG, "${texts.size} fiches vectorisées en ${SystemClock.elapsedRealtime() - start} ms")
        return out
    }

    /**
     * Ferme le moteur et remet l'état à zéro (y compris un échec de chargement). Ne bloque pas : si un calcul est en
     * cours, la libération a lieu dès qu'il se termine.
     */
    fun release() {
        releasePending = true
        releaseIfPending()
    }

    /**
     * Exécute [block] sous le verrou, moteur chargé au besoin ; null si le moteur est indisponible ou si [proceed]
     * (vérifié sous le verrou, avant tout chargement) renvoie false.
     */
    private suspend fun <T : Any> withEngine(proceed: () -> Boolean = { true }, block: (EmbeddingEngine) -> T?): T? =
        withContext(Dispatchers.Default) {
            try {
                mutex.withLock {
                    try {
                        if (proceed()) ensureEngine()?.let(block) else null
                    } finally {
                        if (releasePending) closeEngine()
                    }
                }
            } finally {
                // release() a pu échouer à prendre le verrou juste avant qu'on le rende.
                releaseIfPending()
            }
        }

    private fun releaseIfPending() {
        if (releasePending && mutex.tryLock()) {
            try {
                if (releasePending) closeEngine()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** Sous [mutex]. */
    private fun ensureEngine(): EmbeddingEngine? {
        engine?.let { return it }
        if (loadFailed) return null
        if (!modelFile.isFile || modelFile.length() == 0L) return null
        val start = SystemClock.elapsedRealtime()
        var created: EmbeddingEngine? = null
        return try {
            val e = EmbeddingEngine(
                EmbeddingEngineConfig(modelPath = modelFile.path, backend = Backend.CPU(), cacheDir = cacheDir),
            )
            created = e
            e.initialize()
            Log.i(TAG, "Modèle d'embeddings chargé (CPU) en ${SystemClock.elapsedRealtime() - start} ms")
            engine = e
            e
        } catch (t: Throwable) {
            Log.w(TAG, "Échec du chargement du modèle d'embeddings ${modelFile.path}", t)
            loadFailed = true
            created?.let { closeQuietly(it) }
            null
        }
    }

    /** Sous [mutex]. */
    private fun closeEngine() {
        engine?.let {
            closeQuietly(it)
            Log.i(TAG, "Modèle d'embeddings libéré")
        }
        engine = null
        loadFailed = false
        releasePending = false
        generation++
    }

    /** `close()` lève une exception sur un moteur non initialisé : on vérifie d'abord. */
    private fun closeQuietly(e: EmbeddingEngine) {
        try {
            if (e.isInitialized()) e.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Échec de la fermeture du moteur d'embeddings", t)
        }
    }

    companion object {
        private const val TAG = "LiteRtTextEmbedder"
        /** Fiches par appel à computeEmbeddingBatch. */
        private const val BATCH = 16
    }
}

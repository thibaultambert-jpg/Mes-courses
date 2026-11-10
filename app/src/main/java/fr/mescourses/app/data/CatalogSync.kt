package fr.mescourses.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogUpdater
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** État du catalogue affiché dans les Réglages. */
data class CatalogStatus(
    val count: Int = 0,
    val bySite: Map<String, Int> = emptyMap(),
    /** Date (ms) de la dernière recherche de nouveautés ; null = jamais. */
    val lastUpdate: Long? = null,
    /** Travail en cours (« Nouveautés de Marmiton… ») ; null = au repos. */
    val working: String? = null,
    val lastResult: String? = null,
)

/**
 * Remplit et tient à jour le catalogue local : au lancement, charge le fichier embarqué dans l'appli s'il est nouveau,
 * puis cherche les nouveautés des sites, au plus une fois par jour.
 */
class CatalogSync(
    private val context: Context,
    private val store: RoomCatalogStore,
    private val scope: CoroutineScope,
    private val http: HttpClient,
) {
    val status = MutableStateFlow(CatalogStatus())
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val updater = CatalogUpdater(store, ::fetch)

    /** Au lancement de l'appli. Ne bloque rien : tout se passe en arrière-plan. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            exclusive("Chargement du catalogue…") { importAssets() }
            // Laisse l'appli démarrer avant de solliciter le réseau ; pas de mise à jour d'office en données mobiles.
            delay(15_000)
            if (updater.due() && !metered()) update()
        }
    }

    /** Bouton « Mettre à jour maintenant » des Réglages. */
    fun updateNow() {
        scope.launch(Dispatchers.IO) { update() }
    }

    private suspend fun update() = exclusive("Recherche des nouveautés…") {
        val report = updater.update { message -> status.value = status.value.copy(working = message) }
        buildString {
            append(if (report.added == 0) "Aucune nouvelle recette" else "${report.added} nouvelle${if (report.added > 1) "s" else ""} recette${if (report.added > 1) "s" else ""}")
            if (report.failedSites.isNotEmpty()) append(" · sans réponse : ${report.failedSites.joinToString(", ")}")
        }
    }

    /** Un seul travail à la fois ; l'état est relu à la fin, même en cas d'erreur. */
    private suspend fun exclusive(label: String, work: suspend () -> String?) {
        if (!lock.tryLock()) return
        try {
            status.value = status.value.copy(working = label)
            val result = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, label, e)
                "Échec : ${e.message}"
            }
            refresh(result)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun refresh(result: String?) {
        status.value = CatalogStatus(
            count = store.count(),
            bySite = store.countBySite(),
            lastUpdate = store.meta(CatalogUpdater.LAST_UPDATE)?.toLongOrNull(),
            working = null,
            lastResult = result ?: status.value.lastResult,
        )
    }

    /** Fichiers `assets/catalog/<site>.jsonl(.gz)` : chargés une fois par version (`assets/catalog/version.txt`). */
    private suspend fun importAssets(): String? = withContext(Dispatchers.IO) {
        val assets = context.assets
        val files = assets.list(DIR).orEmpty().filter { it.endsWith(".jsonl") || it.endsWith(".jsonl.gz") }.sorted()
        if (files.isEmpty()) return@withContext null
        val version = runCatching { assets.open("$DIR/version.txt").bufferedReader().use { it.readText().trim() } }.getOrDefault("1")
        var added = 0
        for (file in files) {
            val key = "fichier:$file"
            if (store.meta(key) == version) continue
            val input = BufferedInputStream(assets.open("$DIR/$file"))
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            (if (gzip) GZIPInputStream(input) else input).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.chunked(400).forEach { part ->
                    val entries = part.mapNotNull { line -> runCatching { json.decodeFromString(CatalogEntry.serializer(), line) }.getOrNull() }
                    store.upsert(entries)
                    added += entries.size
                    status.value = status.value.copy(working = "Chargement du catalogue… ($added recettes)")
                }
            }
            store.setMeta(key, version)
        }
        if (added > 0) "$added recettes chargées depuis l'appli" else null
    }

    /** Texte d'une page ou d'un sitemap (les sitemaps .gz sont décompressés). */
    private suspend fun fetch(url: String): String {
        val response = http.get(url) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "fr-FR,fr;q=0.9")
        }
        check(response.status.isSuccess()) { "La page a répondu ${response.status.value}" }
        val bytes: ByteArray = response.body()
        val plain = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        } else bytes
        return String(plain, Charsets.UTF_8)
    }

    private fun metered(): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    private companion object {
        const val TAG = "CatalogSync"
        const val DIR = "catalog"
    }
}

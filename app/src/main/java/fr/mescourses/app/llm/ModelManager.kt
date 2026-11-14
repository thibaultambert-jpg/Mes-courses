package fr.mescourses.app.llm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelInfo(
    val id: String,
    val label: String,
    val description: String,
    val fileName: String,
    val url: String,
    val approxSizeGb: Double,
)

/**
 * Modèles conseillés pour le Honor Magic 5 Pro (Snapdragon 8 Gen 2, 12 Go).
 * Les fichiers .litertlm sont publiés par la communauté LiteRT sur Hugging Face ;
 * si une adresse change, on peut la corriger dans Réglages ou importer le fichier à la main.
 */
object ModelCatalog {
    val models = listOf(
        ModelInfo(
            id = "gemma4-e4b",
            label = "Gemma 4 E4B (recommandé)",
            description = "Meilleure qualité, très bon français et appels d'outils. ~10-14 mots/s sur GPU.",
            fileName = "gemma-4-E4B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            approxSizeGb = 3.6,
        ),
        ModelInfo(
            id = "gemma4-e2b",
            label = "Gemma 4 E2B (rapide)",
            description = "Deux fois plus rapide, un peu moins fiable sur les outils.",
            fileName = "gemma-4-E2B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            approxSizeGb = 2.0,
        ),
    )
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val label: String, val downloaded: Long, val total: Long) : DownloadState {
        val progress: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Done(val path: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class ModelManager(private val context: Context, private val scope: CoroutineScope) {

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state
    private var job: Job? = null

    fun installed(): List<File> = modelsDir.listFiles { f -> f.extension == "litertlm" }?.sortedBy { it.name }.orEmpty()

    fun freeSpaceGb(): Double = modelsDir.usableSpace / 1e9

    /** Téléchargement en arrière-plan (reprise possible grâce au fichier .part). */
    fun download(
        model: ModelInfo,
        url: String = model.url,
        hfToken: String = "",
        dir: File = modelsDir,
        expectedSha256: String = "",
        onDone: (String) -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val target = File(dir, model.fileName)
            try {
                downloadResumable(url, target, hfToken, expectedSha256) { downloaded, total ->
                    _state.value = DownloadState.Running(model.label, downloaded, total)
                }
                _state.value = DownloadState.Done(target.absolutePath)
                withContext(Dispatchers.Main) { onDone(target.absolutePath) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DownloadState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = DownloadState.Idle
    }

    /** Import d'un fichier .litertlm déjà présent sur le téléphone (téléchargé depuis un PC, par ex.). */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.endsWith(".litertlm") } ?: "modele-importe.litertlm"
        val target = File(modelsDir, name)
        _state.value = DownloadState.Running("Import", 0, 0)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Fichier illisible" }
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        _state.value = DownloadState.Done(target.absolutePath)
        target.absolutePath
    }

    fun delete(file: File) { file.delete() }

    companion object {
        /**
         * Téléchargement reprenable (en-tête HTTP `Range`, fichier `.part`, suivi manuel des redirections) avec
         * vérification optionnelle de l'empreinte SHA-256 avant renommage vers [target].
         */
        suspend fun downloadResumable(
            url: String,
            target: File,
            hfToken: String = "",
            expectedSha256: String = "",
            onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        ): File = withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            var connection = openFollowingRedirects(url, hfToken, part.length())
            try {
                if (connection.responseCode == 416 && part.exists()) {
                    connection.disconnect()
                    part.delete()
                    connection = openFollowingRedirects(url, hfToken, 0L)
                }
                val code = connection.responseCode
                if (code == 401 || code == 403) error("Accès refusé ($code) : accepte la licence Gemma sur Hugging Face et renseigne ton jeton HF.")
                if (code !in 200..299) error("Erreur HTTP $code")
                val append = code == 206
                if (!append) part.delete()
                val total = (if (append) part.length() else 0L) + connection.contentLengthLong.coerceAtLeast(0)
                var downloaded = if (append) part.length() else 0L
                onProgress(downloaded, total)
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buffer = ByteArray(1 shl 16)
                        var lastEmit = downloaded
                        while (isActive) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            downloaded += n
                            if (downloaded - lastEmit > 1_000_000) {
                                lastEmit = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                ensureActive()
                if (total > 0 && downloaded < total) error("Téléchargement incomplet ($downloaded / $total octets)")
                if (expectedSha256.isNotBlank()) {
                    val actual = sha256(part)
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        part.delete()
                        error("Fichier corrompu : empreinte SHA-256 invalide (attendu ${expectedSha256.take(12)}…, reçu ${actual.take(12)}…)")
                    }
                }
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    error("Impossible d'enregistrer ${target.name}")
                }
                onProgress(downloaded, total)
                target
            } finally {
                connection.disconnect()
            }
        }

        private fun openFollowingRedirects(url: String, hfToken: String, offset: Long): HttpURLConnection {
            var currentUrl = url
            var connection = openConnection(currentUrl, hfToken, offset)
            // Hugging Face redirige vers un CDN : on suit manuellement pour garder l'en-tête Range.
            var redirects = 0
            while (connection.responseCode in 300..399 && redirects++ < 5) {
                val next = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                currentUrl = URL(URL(currentUrl), next).toString()
                val tokenForHost = if (URL(currentUrl).host.endsWith("huggingface.co")) hfToken else ""
                connection = openConnection(currentUrl, tokenForHost, offset)
            }
            return connection
        }

        private fun openConnection(url: String, token: String, offset: Long): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

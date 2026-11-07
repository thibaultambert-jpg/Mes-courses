package fr.mescourses.app.llm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    fun download(model: ModelInfo, url: String = model.url, hfToken: String = "", onDone: (String) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val target = File(modelsDir, model.fileName)
            val part = File(modelsDir, model.fileName + ".part")
            try {
                var connection = open(url, hfToken, part.length())
                // Hugging Face redirige vers un CDN : on suit manuellement pour garder l'en-tête Range.
                var redirects = 0
                while (connection.responseCode in 300..399 && redirects++ < 5) {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = open(URL(URL(url), next).toString(), if (next.contains("huggingface.co")) hfToken else "", part.length())
                }
                val code = connection.responseCode
                if (code == 401 || code == 403) error("Accès refusé ($code) : accepte la licence Gemma sur Hugging Face et renseigne ton jeton HF.")
                if (code !in 200..299) error("Erreur HTTP $code")
                val append = code == 206
                if (!append) part.delete()
                val total = (if (append) part.length() else 0L) + connection.contentLengthLong.coerceAtLeast(0)
                var downloaded = if (append) part.length() else 0L
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buffer = ByteArray(1 shl 16)
                        var lastEmit = 0L
                        while (isActive) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            downloaded += n
                            if (downloaded - lastEmit > 4_000_000) {
                                lastEmit = downloaded
                                _state.value = DownloadState.Running(model.label, downloaded, total)
                            }
                        }
                    }
                }
                if (!isActive) return@launch
                if (total > 0 && downloaded < total) error("Téléchargement incomplet")
                target.delete()
                part.renameTo(target)
                _state.value = DownloadState.Done(target.absolutePath)
                withContext(Dispatchers.Main) { onDone(target.absolutePath) }
            } catch (e: Exception) {
                _state.value = DownloadState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = DownloadState.Idle
    }

    private fun open(url: String, token: String, offset: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 60_000
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
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
}

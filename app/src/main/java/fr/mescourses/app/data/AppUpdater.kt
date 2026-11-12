package fr.mescourses.app.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** État de la mise à jour de l'appli, affiché dans les Réglages. */
data class UpdateStatus(
    val installed: Long = 0,
    /** Dernière version publiée (numéro de build) ; null = pas encore cherchée. */
    val available: Long? = null,
    val working: String? = null,
    val message: String? = null,
    /** APK téléchargé, prêt à installer. */
    val ready: File? = null,
) {
    val updateAvailable get() = available != null && available > installed
}

/**
 * Met l'appli à jour depuis elle-même : la CI publie l'APK dans la release « appli » (le corps de la release contient
 * le numéro de build). L'appli compare, télécharge, puis ouvre l'installeur Android (une confirmation reste demandée).
 * Les mises à jour ne s'installent par-dessus que si l'APK est signé avec la même clé (secrets de signature de la CI).
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val http: HttpClient,
) {
    val status = MutableStateFlow(UpdateStatus(installed = installedVersion()))
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Asset(val name: String = "", val browser_download_url: String = "")
    @Serializable private data class Release(val body: String? = null, val assets: List<Asset> = emptyList())

    /** Au lancement : cherche une nouvelle version (hors données mobiles), sans rien télécharger. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            delay(20_000)
            if (!metered()) check()
        }
    }

    fun checkNow() {
        scope.launch(Dispatchers.IO) { check() }
    }

    private suspend fun check() {
        if (status.value.working != null) return
        status.value = status.value.copy(working = "Recherche d'une nouvelle version…")
        val result = try {
            val release = release()
            val version = release.body?.trim()?.toLongOrNull() ?: error("numéro de version illisible")
            status.value = status.value.copy(available = version)
            if (version > status.value.installed) "Version $version disponible" else "L'appli est à jour"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG, "recherche", e)
            "Recherche impossible : ${e.message}"
        }
        status.value = status.value.copy(working = null, message = result)
    }

    /** Télécharge l'APK puis lance l'installation. */
    fun install() {
        scope.launch(Dispatchers.IO) {
            if (status.value.working != null) return@launch
            val ready = status.value.ready
            if (ready != null && ready.exists()) { status.value = status.value.copy(message = launchInstaller(ready)); return@launch }
            status.value = status.value.copy(working = "Téléchargement…")
            val result = try {
                val release = release()
                val version = release.body?.trim()?.toLongOrNull() ?: error("numéro de version illisible")
                val url = release.assets.firstOrNull { it.name.endsWith(".apk") }?.browser_download_url ?: error("pas d'APK dans la release")
                val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "mes-courses.apk")
                download(url, file)
                status.value = status.value.copy(available = version, ready = file)
                launchInstaller(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG, "téléchargement", e)
                "Échec : ${e.message}"
            }
            status.value = status.value.copy(working = null, message = result)
        }
    }

    private suspend fun release(): Release {
        val response = http.get("https://api.github.com/repos/$REPO/releases/tags/$TAG_NAME") {
            header("Accept", "application/vnd.github+json")
        }
        check(response.status.isSuccess()) { "GitHub a répondu ${response.status.value}" }
        return json.decodeFromString(Release.serializer(), response.body<String>())
    }

    private suspend fun download(url: String, target: File) = withContext(Dispatchers.IO) {
        val response = http.get(url)
        check(response.status.isSuccess()) { "Téléchargement refusé (${response.status.value})" }
        response.bodyAsChannel().toInputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        check(target.length() > 1_000_000) { "fichier incomplet" }
    }

    /** Ouvre l'installeur Android ; la 1re fois, il faut autoriser « Mes courses » à installer des applis. */
    private fun launchInstaller(file: File): String? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Autorise « Mes courses » à installer des applis, puis reviens appuyer sur Mettre à jour"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fichiers", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return null
    }

    private fun installedVersion(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun metered(): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    private companion object {
        const val LOG = "AppUpdater"
        const val REPO = "thibaultambert-jpg/Mes-courses"
        const val TAG_NAME = "appli"
    }
}

package fr.mescourses.tools.catalog

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogSource
import fr.mescourses.core.catalog.CatalogSources
import fr.mescourses.core.catalog.SiteCrawler
import fr.mescourses.core.recipes.RecipeSites
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import kotlin.system.exitProcess

/**
 * Construit le catalogue local de recettes, site par site, en plusieurs passages.
 *
 * `build --site <id> --out <dossier> [--minutes 330] [--rate N] [--limit N]`
 *   Lit les fiches du site qui ne l'ont pas encore été et s'arrête au bout du temps donné. Dans le dossier :
 *   - `<id>.jsonl`    une recette du catalogue par ligne (JSON) ;
 *   - `<id>.seen.tsv` chaque adresse déjà traitée, sa date de modification et le résultat (ok, vide, erreur), puis le
 *     message d'erreur et l'adresse d'origine ;
 *   - `<id>.erreurs.tsv` les fiches encore en erreur à la fin du passage (adresse, message), toutes, pas seulement les premières.
 *   Relancer la commande reprend où le passage précédent s'est arrêté ; une fiche modifiée depuis est relue.
 *
 * `stats --out <dossier>` : compte les recettes par site, type de plat et note.
 */
fun main(args: Array<String>) {
    val options = args.drop(1).chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].drop(2) to it[1] }
    val out = File(options["out"] ?: "catalog").apply { mkdirs() }
    when (args.firstOrNull()) {
        "build" -> {
            val site = options["site"] ?: fail("--site manquant (${CatalogSources.all.joinToString { it.siteId }})")
            val source = CatalogSources.byId(site) ?: fail("site inconnu : $site")
            // S'il reste des fiches, le passage suivant reprend ici : ce n'est pas une erreur.
            runBlocking {
                Builder(source, out, options["rate"]?.toDoubleOrNull() ?: source.requestsPerSecond)
                    .run(Duration.ofMinutes(options["minutes"]?.toLongOrNull() ?: 330), options["limit"]?.toIntOrNull() ?: Int.MAX_VALUE)
            }
        }
        "stats" -> stats(out)
        else -> fail("commandes : build, stats")
    }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** Client HTTP lent et poli : un rythme fixe, les mêmes en-têtes que l'appli, les .gz décompressés. */
private class Http(requestsPerSecond: Double) {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    private val pauseMs = (1000 / requestsPerSecond).toLong()
    private var last = 0L

    class Status(val code: Int) : Exception("HTTP $code")

    suspend fun get(url: String): String {
        delay((last + pauseMs - System.currentTimeMillis()).coerceAtLeast(0))
        last = System.currentTimeMillis()
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) throw Status(response.statusCode())
        val bytes = response.body()
        val plain = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        } else bytes
        return String(plain, Charsets.UTF_8)
    }
}

/** Erreurs de suite au bout desquelles on n'insiste plus pendant ce passage. */
private const val MAX_STREAK = 10

private class Builder(private val source: CatalogSource, out: File, rate: Double) {
    private val http = Http(rate)
    private val crawler = SiteCrawler(http::get)
    private val entries = File(out, "${source.siteId}.jsonl")
    private val seenFile = File(out, "${source.siteId}.seen.tsv")

    /** Adresse normalisée → date de modification vue au dernier passage (les erreurs ne comptent pas : on réessaie). */
    private val seen: MutableMap<String, String> = HashMap<String, String>().apply {
        if (seenFile.exists()) seenFile.forEachLine { line ->
            val (id, lastmod, status) = line.split('\t').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            if (status != "erreur") put(id, lastmod)
        }
        // Pas d'état (cache d'une autre branche, par exemple) mais le fichier d'une collecte précédente : ses fiches comptent
        // comme lues. Elles sont inscrites dans l'état, pour que les passages suivants ne les relisent pas.
        else if (this@Builder.entries.exists()) {
            val lines = StringBuilder()
            this@Builder.entries.forEachLine { line ->
                val entry = runCatching { json.decodeFromString(CatalogEntry.serializer(), line) }.getOrNull() ?: return@forEachLine
                val id = RecipeSites.normalize(entry.url)
                put(id, entry.lastmod.orEmpty())
                lines.append("$id\t${entry.lastmod.orEmpty()}\tok\t\t${entry.url}\n")
            }
            seenFile.writeText(lines.toString())
            println("État repris du fichier ${this@Builder.entries.name} : $size fiches déjà lues")
        }
    }

    /** Fiches dont le dernier résultat est une erreur : adresse normalisée → « adresse d'origine \t message ». */
    private val pending: MutableMap<String, String> = LinkedHashMap<String, String>().apply {
        if (seenFile.exists()) seenFile.forEachLine { line ->
            val cols = line.split('\t')
            if (cols.size < 3) return@forEachLine
            if (cols[2] == "erreur") put(cols[0], "${cols.getOrElse(4) { "https://" + cols[0] }}\t${cols.getOrElse(3) { "" }}") else remove(cols[0])
        }
    }
    private val errorsFile = File(out, "${source.siteId}.erreurs.tsv")

    /** true si tout le site a été lu, false s'il reste des fiches pour un prochain passage. */
    suspend fun run(budget: Duration, limit: Int): Boolean {
        val deadline = System.currentTimeMillis() + budget.toMillis()
        val pages = crawler.pages(source)
        val todo = pages.filter { p -> seen[RecipeSites.normalize(p.url)].let { it == null || (p.lastmod != null && p.lastmod != it) } }
        println("${source.siteId} : ${pages.size} fiches annoncées, ${pages.size - todo.size} déjà lues, ${todo.size} à lire")
        var ok = 0
        var empty = 0
        var errors = 0
        var streak = 0
        var count = 0
        for (page in todo) {
            if (System.currentTimeMillis() > deadline || count >= limit) break
            count++
            var detail = ""
            val status = try {
                val entry = crawler.read(source, page)
                streak = 0
                if (entry != null) {
                    entries.appendText(json.encodeToString(CatalogEntry.serializer(), entry) + "\n")
                    ok++
                    "ok"
                } else { empty++; "vide" }
            } catch (e: Exception) {
                errors++
                streak++
                detail = e.message.orEmpty().replace(Regex("[\\t\\r\\n]+"), " ")
                // Le site refuse ou ralentit : on attend de plus en plus, puis on abandonne ce passage.
                val code = (e as? Http.Status)?.code
                if (code == 403 || code == 429 || code == 503) delay((60_000L * streak).coerceAtMost(600_000L))
                if (errors <= 50) println("  erreur ${e.message} — ${page.url}")
                // Une page disparue ne reviendra pas : inutile de la redemander à chaque passage.
                if (code == 404 || code == 410) "vide" else "erreur"
            }
            val id = RecipeSites.normalize(page.url)
            seenFile.appendText("$id\t${page.lastmod.orEmpty()}\t$status\t$detail\t${page.url}\n")
            if (status == "erreur") pending[id] = "${page.url}\t$detail" else pending.remove(id)
            if (count % 500 == 0) println("  $count/${todo.size} — $ok recettes, $empty sans recette, $errors erreurs")
            if (streak >= MAX_STREAK) {
                println("  $MAX_STREAK erreurs de suite : arrêt de ce passage pour ${source.siteId}")
                break
            }
        }
        // Toutes les fiches encore en erreur (y compris celles des passages précédents) : de quoi programmer un rattrapage.
        errorsFile.writeText(pending.values.joinToString("") { it + "\n" })
        println("${source.siteId} : ${pending.size} fiches encore en erreur (${errorsFile.name})")
        val left = todo.size - count
        println("${source.siteId} : $ok recettes ajoutées, $empty pages sans recette, $errors erreurs, $left fiches restantes")
        return left <= 0 && streak < MAX_STREAK
    }
}

private fun stats(out: File) {
    println("| Site | Recettes | Plats, soupes, salades | Bien notées | Avec note | Sans durée |")
    println("|---|---|---|---|---|---|")
    var total = 0
    var bytes = 0L
    out.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }.forEach { file ->
        // La dernière lecture d'une fiche l'emporte (une fiche modifiée est relue).
        val byId = LinkedHashMap<String, CatalogEntry>()
        file.forEachLine { line -> runCatching { json.decodeFromString(CatalogEntry.serializer(), line) }.getOrNull()?.let { byId[it.id] = it } }
        val list = byId.values
        total += list.size
        bytes += file.length()
        println("| ${file.nameWithoutExtension} | ${list.size} | ${list.count { it.course.meal }} | " +
            "${list.count { fr.mescourses.core.catalog.CatalogPolicy.wellRated(it) }} | ${list.count { it.rating != null }} | ${list.count { it.totalMinutes == null }} |")
    }
    println("\nTotal : $total recettes, ${bytes / 1_000_000} Mo de JSON")
}


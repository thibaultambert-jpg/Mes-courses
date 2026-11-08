package fr.mescourses.core.recipes

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Une recette trouvée dans les résultats de recherche d'un site (pas encore importée). */
data class RecipeHit(
    val title: String,
    val url: String,
    val siteId: String,
    val siteName: String,
    val imageUrl: String? = null,
    /** Note sur 5, si le site l'affiche. */
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val totalMinutes: Int? = null,
)

/** Récupère le HTML d'une page (client HTTP, ou WebView quand le site bloque les robots). */
fun interface PageFetcher {
    suspend fun fetch(url: String): String
}

/**
 * Lit une page de résultats de recherche.
 *
 * Ne dépend pas de la mise en page exacte du site : on garde les liens dont l'adresse a la forme d'une
 * recette du site ([RecipeSite.recipeUrl]), et on cherche titre, image et note dans la « carte » qui les entoure.
 * Une liste JSON-LD (ItemList), quand le site en publie une, complète le résultat.
 */
object RecipeSearchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(html: String, pageUrl: String, site: RecipeSite, limit: Int = 24): List<RecipeHit> {
        val doc = Jsoup.parse(html, pageUrl)
        val byUrl = LinkedHashMap<String, RecipeHit>()

        fun merge(hit: RecipeHit) {
            val key = RecipeSites.normalize(hit.url)
            val old = byUrl[key]
            byUrl[key] = if (old == null) hit else old.copy(
                title = listOf(old.title, hit.title).maxBy { titleScore(it) },
                imageUrl = old.imageUrl ?: hit.imageUrl,
                rating = old.rating ?: hit.rating,
                ratingCount = old.ratingCount ?: hit.ratingCount,
                totalMinutes = old.totalMinutes ?: hit.totalMinutes,
            )
        }

        for (a in doc.select("a[href]")) {
            val url = a.absUrl("href").substringBefore('#')
            if (url.isEmpty() || !site.isRecipeUrl(url)) continue
            val card = cardOf(a, site)
            val title = titleOf(a, card) ?: continue
            merge(
                RecipeHit(
                    title = title,
                    url = url,
                    siteId = site.id,
                    siteName = site.name,
                    imageUrl = imageOf(a) ?: card?.let { imageOf(it) },
                    rating = card?.let { ratingOf(it.text()) },
                    ratingCount = card?.let { ratingCountOf(it.text()) },
                    totalMinutes = card?.let { minutesOf(it.text()) },
                ),
            )
        }

        for (script in doc.select("script[type=application/ld+json]")) {
            val element = runCatching { json.parseToJsonElement(script.data().trim()) }.getOrNull() ?: continue
            itemList(element).forEach { item ->
                val url = item.str("url")?.let { resolve(pageUrl, it) } ?: return@forEach
                if (!site.isRecipeUrl(url)) return@forEach
                val name = item.str("name") ?: (item["item"] as? JsonObject)?.str("name") ?: return@forEach
                merge(RecipeHit(clean(name), url, site.id, site.name, imageUrl = item.str("image")))
            }
        }

        return byUrl.values.filter { it.title.length >= 3 }.take(limit)
    }

    /** Plus petit ancêtre qui ne contient qu'une seule recette : la « carte » du résultat. */
    private fun cardOf(a: Element, site: RecipeSite): Element? {
        val target = RecipeSites.normalize(a.absUrl("href"))
        var best: Element? = null
        var current = a.parent()
        var depth = 0
        while (current != null && depth < 5) {
            val recipes = current.select("a[href]").map { it.absUrl("href") }
                .filter { site.isRecipeUrl(it) }.map { RecipeSites.normalize(it) }.toSet()
            if (recipes.size > 1 || recipes.singleOrNull() != target) break
            best = current
            current = current.parent()
            depth++
        }
        return best
    }

    private fun titleOf(a: Element, card: Element?): String? {
        val candidates = buildList {
            listOf(a, card).forEach { scope ->
                scope ?: return@forEach
                scope.select("h1,h2,h3,h4,h5,[class*=title],[class*=Title],[class*=name]").forEach { add(it.text()) }
            }
            add(a.attr("title"))
            add(a.attr("aria-label"))
            add(a.ownText())
            add(a.text())
            a.select("img[alt]").forEach { add(it.attr("alt")) }
        }
        return candidates.map(::clean).filter { it.length in 3..140 && !isNoise(it) }.firstOrNull()
    }

    private fun isNoise(text: String): Boolean {
        val t = text.lowercase()
        return t in setOf("voir la recette", "lire la suite", "recette", "ajouter", "en savoir plus") ||
            Regex("""^[\d\s.,/★☆()]+(avis)?$""").matches(t)
    }

    private fun titleScore(t: String): Int = if (isNoise(t)) -1 else t.length.coerceAtMost(80)

    private fun imageOf(scope: Element): String? {
        for (img in scope.select("img, source")) {
            val candidates = listOf("data-src", "data-lazy-src", "data-original", "src", "data-srcset", "srcset")
                .map { img.attr(it).trim() }
                .map { if (' ' in it || ',' in it) it.substringBefore(',').trim().substringBefore(' ') else it }
            val src = candidates.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") } ?: continue
            if (src.contains("logo", ignoreCase = true) || src.endsWith(".svg")) continue
            return resolve(scope.baseUri(), src)
        }
        return null
    }

    fun ratingOf(text: String): Double? =
        Regex("""(\d(?:[.,]\d)?)\s*/\s*5""").find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 0.0..5.0 }

    fun ratingCountOf(text: String): Int? =
        Regex("""\(?\s*(\d[\d\s ]*)\s*(?:avis|commentaires?|votes?|notes?)\b""", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull()

    /** « 1 h 15 min », « 45 min », « 1h » → minutes. */
    fun minutesOf(text: String): Int? {
        val m = Regex("""(?:(\d+)\s*h\s*(\d+)?)|(?:(\d+)\s*min)""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val (h, hm, min) = m.destructured
        val total = if (h.isNotEmpty()) h.toInt() * 60 + (hm.toIntOrNull() ?: 0) else min.toInt()
        return total.takeIf { it in 1..1440 }
    }

    private fun itemList(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap { itemList(it) }
        is JsonObject -> when {
            element["@graph"] != null -> itemList(element["@graph"]!!)
            element["itemListElement"] is JsonArray -> (element["itemListElement"] as JsonArray).mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        is JsonObject -> (v["url"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun resolve(base: String, href: String): String =
        runCatching { java.net.URI(base).resolve(href.trim()).toString() }.getOrDefault(href)

    private fun clean(text: String): String = text.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
}

/** Résultat d'une recherche sur plusieurs sites. */
data class RecipeSearchResult(
    val hits: List<RecipeHit>,
    /** Sites qui n'ont pas répondu, avec la raison. */
    val failures: Map<RecipeSite, String>,
    /** Sites qui ont répondu sans résultat lisible. */
    val empty: List<RecipeSite>,
)

/**
 * Cherche sur plusieurs sites en parallèle et mélange les résultats (un de chaque site à tour de rôle).
 * Pour chaque site, les [fetchers] sont essayés dans l'ordre tant qu'aucun résultat n'est lu.
 */
class RecipeSearcher(private val fetchers: List<PageFetcher>) {
    constructor(fetcher: PageFetcher) : this(listOf(fetcher))

    suspend fun search(query: String, sites: List<RecipeSite>, perSite: Int = 12): RecipeSearchResult = coroutineScope {
        val jobs = sites.filter { it.searchable }.map { site ->
            site to async {
                runCatching { searchSite(query, site, perSite) }
            }
        }
        val results = jobs.map { (site, job) -> site to job.await() }
        val lists = results.mapNotNull { (_, r) -> r.getOrNull()?.takeIf { it.isNotEmpty() } }
        RecipeSearchResult(
            hits = interleave(lists),
            failures = results.mapNotNull { (site, r) -> r.exceptionOrNull()?.let { site to (it.message ?: it.toString()) } }.toMap(),
            empty = results.filter { (_, r) -> r.getOrNull()?.isEmpty() == true }.map { it.first },
        )
    }

    /** Recettes populaires du moment : page « populaires » du site, ou sa page d'accueil. */
    suspend fun popular(sites: List<RecipeSite>, perSite: Int = 12): RecipeSearchResult = coroutineScope {
        val jobs = sites.map { site -> site to async { runCatching { fetchHits(site.popularPage, site, perSite) } } }
        val results = jobs.map { (site, job) -> site to job.await() }
        RecipeSearchResult(
            hits = interleave(results.mapNotNull { (_, r) -> r.getOrNull()?.takeIf { it.isNotEmpty() } }),
            failures = results.mapNotNull { (site, r) -> r.exceptionOrNull()?.let { site to (it.message ?: it.toString()) } }.toMap(),
            empty = results.filter { (_, r) -> r.getOrNull()?.isEmpty() == true }.map { it.first },
        )
    }

    private suspend fun searchSite(query: String, site: RecipeSite, perSite: Int): List<RecipeHit> =
        fetchHits(site.searchUrlFor(query)!!, site, perSite)

    private suspend fun fetchHits(url: String, site: RecipeSite, perSite: Int): List<RecipeHit> {
        var lastError: Exception? = null
        var answered = false
        for (fetcher in fetchers) {
            try {
                val hits = RecipeSearchParser.parse(fetcher.fetch(url), url, site, perSite)
                answered = true
                if (hits.isNotEmpty()) return hits
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        if (!answered && lastError != null) throw lastError
        return emptyList()
    }

    companion object {
        fun <T> interleave(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
            return out
        }
    }
}

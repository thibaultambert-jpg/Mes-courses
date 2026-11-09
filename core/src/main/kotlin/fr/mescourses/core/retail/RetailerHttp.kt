package fr.mescourses.core.retail

import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.HttpResponse
import fr.mescourses.core.carrefour.HttpTransport
import kotlinx.coroutines.delay

/**
 * Appels HTTP communs à Leclerc et Auchan, par le [HttpTransport] (WebView connectée) : routes interdites,
 * traduction des erreurs, 429 réessayé une fois après une pause. `classify` reconnaît les réponses
 * propres à l'enseigne (anti-robot, session expirée) avant la règle générale.
 */
class RetailerHttp(
    private val transport: HttpTransport,
    private val who: String,
    private val forbidden: List<String>,
    private val rateLimitWaitMs: Long = 3_000,
    private val classify: (HttpResponse) -> CarrefourException? = { null },
) {
    suspend fun call(method: String, url: String, headers: Map<String, String>, body: String? = null): HttpResponse {
        if (forbidden.any { url.contains(it) }) throw CarrefourException.Forbidden(url.substringBefore('?'))
        repeat(2) { attempt ->
            val r = try {
                transport.request(method, url, headers, body)
            } catch (e: CarrefourException) {
                throw e
            } catch (e: Exception) {
                throw CarrefourException.Network(e.message ?: e.toString(), who)
            }
            classify(r)?.let { throw it }
            when {
                r.status == 401 -> throw CarrefourException.SessionExpired(who)
                r.status == 429 && attempt == 0 -> delay(rateLimitWaitMs)
                r.status == 429 -> throw CarrefourException.RateLimited(who)
                r.status >= 400 -> throw CarrefourException.Http(r.status, who)
                else -> return r
            }
        }
        throw CarrefourException.RateLimited(who)
    }
}

/** Petits outils de lecture partagés par les lecteurs Leclerc et Auchan. */
internal object Scrape {
    /** « 11,88 € » → 11.88. */
    fun euro(text: String?): Double? {
        val m = Regex("""(\d+(?:[   ]\d{3})*(?:[.,]\d+)?)""").find(text ?: return null) ?: return null
        return m.groupValues[1].replace(Regex("[   ]"), "").replace(',', '.').toDoubleOrNull()
    }

    fun decodeEntities(s: String): String = s
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

    /** Le plus petit objet `{…}` (chaînes respectées) qui contient la position `at`. */
    fun enclosingObject(raw: String, at: Int): String? {
        var start = -1
        var depth = 0
        var i = at
        while (i >= 0) {
            val c = raw[i]
            if (c == '}') depth++
            else if (c == '{') {
                if (depth == 0) { start = i; break }
                depth--
            }
            i--
        }
        if (start < 0) return null
        return balanced(raw, start, '{', '}')
    }

    /** Le littéral `[…]` qui suit exactement la clé `"name":[` (donc `lstProduits` ne prend pas `lstProduitsLight`). */
    fun arrayNamed(raw: String, name: String): String? {
        val marker = "\"$name\":["
        val at = raw.indexOf(marker)
        if (at < 0) return null
        return balanced(raw, at + marker.length - 1, '[', ']')
    }

    private fun balanced(raw: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inStr: Char? = null
        var j = start
        while (j < raw.length) {
            val c = raw[j]
            if (inStr != null) {
                if (c == '\\') j++ else if (c == inStr) inStr = null
            } else if (c == '"') {
                inStr = c
            } else if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0) return raw.substring(start, j + 1)
            }
            j++
        }
        return null
    }
}

package fr.mescourses.core.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Règles de rationalisation du catalogue (toutes ont une valeur par défaut ; un JSON partiel suffit). */
@Serializable
data class RationalizationConfig(
    val requireUniqueImage: Boolean = true,
    val excludedCourses: Set<Course> = setOf(Course.BOISSON),
    val defaultMinRating: Double = 4.5,
    val minRatingBySite: Map<String, Double> = mapOf("hellofresh" to 4.0),
    val minRatingCount: Int = 10,
    val keepUnrated: Boolean = true,
    val requireTime: Boolean = true,
    val minIngredients: Int = 3,
    /** Utilisé par la déduplication par titre (lot 2.1b). */
    val dedupeTitles: Boolean = true,
) {
    companion object {
        private val parser = Json { ignoreUnknownKeys = true }
        fun load(json: String): RationalizationConfig = parser.decodeFromString(serializer(), json)
    }
}

object CatalogRationalizer {
    enum class Reason { TITLE, INGREDIENTS, COURSE, TIME, IMAGE, RATING_COUNT, RATING }

    /** null si la fiche est rejetée ; sinon la fiche recalculée. [seenImages] : images déjà vues, partagé sur tout un passage. */
    fun filterSingle(
        entry: CatalogEntry,
        config: RationalizationConfig = RationalizationConfig(),
        seenImages: MutableSet<String>? = null,
    ): CatalogEntry? = if (reject(entry, config, seenImages) == null) entry.recompute() else null

    /** La raison du rejet, null si la fiche est gardée. L'image n'est retenue que si la fiche passe les tests précédents. */
    fun reject(entry: CatalogEntry, config: RationalizationConfig, seenImages: MutableSet<String>?): Reason? {
        if (entry.title.isBlank()) return Reason.TITLE
        if (entry.ingredients.size < config.minIngredients) return Reason.INGREDIENTS
        if (entry.course in config.excludedCourses) return Reason.COURSE
        if (config.requireTime) {
            val total = entry.totalMinutes
                ?: if (entry.prepMinutes != null || entry.cookMinutes != null) (entry.prepMinutes ?: 0) + (entry.cookMinutes ?: 0) else null
            if (total == null) return Reason.TIME
        }
        val image = entry.imageUrl
        if (config.requireUniqueImage && seenImages != null && !image.isNullOrBlank() && !seenImages.add(image)) return Reason.IMAGE
        val rating = entry.rating
        if (rating != null) {
            val count = entry.ratingCount
            if (count == null || count < config.minRatingCount) return Reason.RATING_COUNT
            if (rating < (config.minRatingBySite[entry.siteId] ?: config.defaultMinRating)) return Reason.RATING
        } else if (!config.keepUnrated) return Reason.RATING
        return null
    }

    data class SiteReport(val read: Int, val rejected: Map<Reason, Int>, val duplicates: Int, val kept: Int)

    data class Report(val sites: Map<String, SiteReport>) {
        fun markdown(): String = buildString {
            val reasons = Reason.entries
            appendLine("| Site | Lu | " + reasons.joinToString(" | ") { it.name } + " | Doublons | Gardé |")
            appendLine("|---|" + "---:|".repeat(reasons.size + 3))
            for ((site, r) in sites) {
                appendLine("| $site | ${r.read} | " + reasons.joinToString(" | ") { "${r.rejected[it] ?: 0}" } + " | ${r.duplicates} | ${r.kept} |")
            }
            val all = sites.values
            appendLine(
                "| **Total** | ${all.sumOf { it.read }} | " + reasons.joinToString(" | ") { reason -> "${all.sumOf { it.rejected[reason] ?: 0 }}" } +
                    " | ${all.sumOf { it.duplicates }} | ${all.sumOf { it.kept }} |"
            )
        }
    }

    data class Result(val kept: List<CatalogEntry>, val report: Report)

    /** Tri (siteId, url), dédoublonnage par id (dernier gagne), filterSingle (images uniques sur tout le passage), titres (hook), rapport. */
    fun process(
        entries: List<CatalogEntry>,
        config: RationalizationConfig = RationalizationConfig(),
        titleDedupe: ((List<CatalogEntry>) -> List<CatalogEntry>)? = null,
    ): Result {
        val byId = LinkedHashMap<String, CatalogEntry>()
        for (e in entries.sortedWith(compareBy({ it.siteId }, { it.url }))) { byId.remove(e.id); byId[e.id] = e }
        val ordered = byId.values.sortedWith(compareBy({ it.siteId }, { it.url }))

        val seenImages = mutableSetOf<String>()
        val read = sortedMapOf<String, Int>()
        val rejected = sortedMapOf<String, MutableMap<Reason, Int>>()
        val passed = ArrayList<CatalogEntry>()
        for (e in ordered) {
            read.merge(e.siteId, 1, Int::plus)
            val reason = reject(e, config, seenImages)
            if (reason == null) passed += e.recompute()
            else rejected.getOrPut(e.siteId) { sortedMapOf() }.merge(reason, 1, Int::plus)
        }
        val kept = if (config.dedupeTitles && titleDedupe != null) titleDedupe(passed) else passed
        val keptBySite = kept.groupingBy { it.siteId }.eachCount()
        val passedBySite = passed.groupingBy { it.siteId }.eachCount()
        val sites = read.mapValues { (site, n) ->
            SiteReport(n, rejected[site] ?: emptyMap(), (passedBySite[site] ?: 0) - (keptBySite[site] ?: 0), keptBySite[site] ?: 0)
        }
        return Result(kept, Report(sites))
    }
}

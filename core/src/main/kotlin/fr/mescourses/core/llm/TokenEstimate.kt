package fr.mescourses.core.llm

/** Estimation chars → tokens pour Gemma en français ; calibrée une fois sur l'appareil avec getTokenCount(). */
object TokenEstimate {
    const val CHARS_PER_TOKEN_TEXT = 3.3
    const val CHARS_PER_TOKEN_JSON = 2.8
    private const val TOOL_WRAPPER_TOKENS = 8

    /** ceil(len / 3.3), 0 pour vide. */
    fun text(s: String): Int = estimate(s, CHARS_PER_TOKEN_TEXT)

    fun json(s: String): Int = estimate(s, CHARS_PER_TOKEN_JSON)

    /** Somme de json() + 8 tokens d'enrobage par outil. */
    fun tools(openApiJsons: List<String>): Int = openApiJsons.sumOf { json(it) + TOOL_WRAPPER_TOKENS }

    // Arithmétique entière : évite que ceil(33 / 3.3) donne 11 à cause de l'arrondi flottant.
    private fun estimate(s: String, charsPerToken: Double): Int {
        if (s.isEmpty()) return 0
        val den = Math.round(charsPerToken * 10)
        return ((s.length * 10L + den - 1) / den).toInt()
    }
}

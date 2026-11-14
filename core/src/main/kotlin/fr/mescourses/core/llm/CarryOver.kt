package fr.mescourses.core.llm

/** Reports compacts : toujours une paire USER (état) / ASSISTANT (accusé) pour respecter l'alternance du chat template. */
object CarryOver {
    const val ACK = "D'accord, je continue à partir de là."
    private const val HEADER = "[Reprise de la conversation précédente]"
    private const val MAX_PRODUCTS = 12
    private const val MIN_CHARS = 20

    fun courses(cartSummary: String?, seenProducts: List<String>, lastAnswer: String?, maxChars: Int = 900): List<Msg> {
        val state = buildList {
            cartSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Panier : $it") }
            val seen = seenProducts.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_PRODUCTS)
            if (seen.isNotEmpty()) add("Produits déjà vus : ${seen.joinToString(" ; ")}")
        }
        return build(state, lastAnswer, maxChars)
    }

    /** context = MenuPrompt.context(draft). */
    fun menu(context: String, lastAnswer: String?, maxChars: Int = 900): List<Msg> {
        val state = context.trim().takeIf { it.isNotEmpty() }?.let { listOf("Menu en cours : $it") }.orEmpty()
        return build(state, lastAnswer, maxChars)
    }

    fun chat(lastAnswer: String?, maxChars: Int = 600): List<Msg> = build(emptyList(), lastAnswer, maxChars)

    /** L'état passe avant la dernière réponse, qui prend la place restante et est tronquée en fin. */
    private fun build(state: List<String>, lastAnswer: String?, maxChars: Int): List<Msg> {
        val answer = lastAnswer?.trim()?.takeIf { it.isNotEmpty() }
        if (state.isEmpty() && answer == null) return emptyList()
        val sb = StringBuilder(HEADER)
        for (section in state) {
            val room = maxChars - sb.length - 1
            if (room < MIN_CHARS) break
            sb.append('\n').append(truncate(section, room))
            if (section.length > room) break
        }
        if (answer != null) {
            val prefix = "\nDernière réponse : « "
            val suffix = " »"
            val room = maxChars - sb.length - prefix.length - suffix.length
            if (room >= MIN_CHARS) sb.append(prefix).append(truncate(answer, room)).append(suffix)
        }
        return listOf(Msg(MsgRole.USER, sb.toString()), Msg(MsgRole.ASSISTANT, ACK))
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}

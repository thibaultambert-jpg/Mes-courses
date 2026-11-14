package fr.mescourses.core.llm

sealed interface Rollover {
    data object Keep : Rollover
    data class Recreate(val reason: String, val initial: List<Msg>) : Rollover
}

/** Décide s'il faut ouvrir une nouvelle conversation avant le prochain tour. */
class ConversationPolicy(private val budget: ContextBudget, private val maxToolTurns: Int = 8) {

    /** tokenCount : valeur exacte du moteur, ou null (→ estimation via transcript + systemTokens).
     *  Recreate si tokens + text(nextMessage) + expectedToolTokens + 300 > budget.softLimit,
     *  ou si le nombre de tours avec toolTokens > 0 atteint maxToolTurns. */
    fun decide(
        tokenCount: Int?,
        transcript: Transcript,
        systemTokens: Int,
        nextMessage: String,
        expectedToolTokens: Int,
        carryOver: () -> List<Msg>,
    ): Rollover {
        val tokens = tokenCount ?: transcript.estimatedTokens(systemTokens)
        val needed = tokens + TokenEstimate.text(nextMessage) + expectedToolTokens + MARGIN
        if (needed > budget.softLimit) {
            return Rollover.Recreate("contexte plein : $needed tokens prévus pour ${budget.softLimit} autorisés", carryOver())
        }
        val toolTurns = transcript.turns.count { it.toolTokens > 0 }
        if (toolTurns >= maxToolTurns) {
            return Rollover.Recreate("$toolTurns tours à outils (max $maxToolTurns)", carryOver())
        }
        return Rollover.Keep
    }

    private companion object {
        const val MARGIN = 300
    }
}

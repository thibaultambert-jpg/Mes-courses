package fr.mescourses.core.llm

enum class MsgRole { USER, ASSISTANT }

data class Msg(val role: MsgRole, val text: String)

/** Ce que l'appli garde d'une conversation (le moteur n'expose pas son historique). */
data class Transcript(val turns: List<Turn> = emptyList()) {
    data class Turn(val user: String, val assistant: String, val toolTokens: Int = 0)

    /** systemTokens + Σ (text(user) + text(assistant) + toolTokens). */
    fun estimatedTokens(systemTokens: Int): Int =
        systemTokens + turns.sumOf { TokenEstimate.text(it.user) + TokenEstimate.text(it.assistant) + it.toolTokens }

    fun plus(turn: Turn): Transcript = Transcript(turns + turn)
}

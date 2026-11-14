package fr.mescourses.core.llm

/** Fenêtre de contexte du moteur (`maxNumTokens`) et marge gardée libre pour la réponse. */
data class ContextBudget(val maxTokens: Int, val reserve: Int) {
    val softLimit: Int get() = maxTokens - reserve

    companion object {
        fun menu(maxTokens: Int) = ContextBudget(maxTokens, reserve = 1_000)
        fun courses(maxTokens: Int) = ContextBudget(maxTokens, reserve = 1_400)
    }
}

package fr.mescourses.app.llm

import fr.mescourses.core.agent.AgentTool
import kotlinx.coroutines.flow.Flow

sealed interface ChatEvent {
    /** Morceau de texte généré (streaming). */
    data class Token(val text: String) : ChatEvent
    data class ToolCall(val name: String, val arguments: String) : ChatEvent
    data class ToolResult(val name: String, val ok: Boolean, val summary: String) : ChatEvent
}

/**
 * Moteur d'inférence local. Implémentation actuelle : LiteRT-LM (Google).
 * Cette interface permet de brancher plus tard xybrid ou llama.cpp pour comparer.
 */
interface LlmEngine {
    /** Charge le modèle ; renvoie le backend réellement utilisé (« GPU » ou « CPU »). */
    suspend fun load(modelPath: String, preferGpu: Boolean): String
    fun isLoaded(): Boolean
    /** Démarre une nouvelle conversation (efface l'historique). */
    fun newConversation(systemPrompt: String, tools: List<AgentTool>)
    fun send(message: String): Flow<ChatEvent>
    fun hasConversation(): Boolean
    /**
     * Génération ponctuelle sans outils (ex. une recette en JSON). Ferme la conversation en cours :
     * il faut rappeler newConversation ensuite.
     */
    suspend fun generateOnce(systemPrompt: String, prompt: String): String
    fun close()
}

package fr.mescourses.app.llm

import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.llm.Msg
import kotlinx.coroutines.flow.Flow

sealed interface ChatEvent {
    /** Morceau de texte généré (streaming). */
    data class Token(val text: String) : ChatEvent
    data class ToolCall(val name: String, val arguments: String) : ChatEvent
    /** [size] : longueur (en caractères) du résultat renvoyé au modèle. */
    data class ToolResult(val name: String, val ok: Boolean, val summary: String, val size: Int = 0) : ChatEvent
}

/**
 * Moteur d'inférence local. Implémentation actuelle : LiteRT-LM (Google).
 * Cette interface permet de brancher plus tard xybrid ou llama.cpp pour comparer.
 */
interface LlmEngine {
    /**
     * Charge le modèle ; renvoie le backend réellement utilisé (« GPU » ou « CPU »).
     * [maxTokens] : taille de contexte demandée ; en cas d'échec, repli sur 4096.
     */
    suspend fun load(modelPath: String, preferGpu: Boolean, maxTokens: Int? = null): String

    /** Taille de contexte réellement chargée (4096 si inconnue). */
    fun maxTokens(): Int
    fun isLoaded(): Boolean

    /**
     * Démarre une nouvelle conversation (efface l'historique ; pas de compaction en place).
     * [initial] : report compact d'une conversation précédente, injecté comme messages initiaux.
     * [prefill] : pré-calcule le prompt système (et [initial]) avant le premier message.
     * [maxOutputTokens] : limite de tokens générés par réponse (null = défaut du moteur).
     */
    fun newConversation(
        systemPrompt: String,
        tools: List<AgentTool>,
        initial: List<Msg> = emptyList(),
        prefill: Boolean = false,
        maxOutputTokens: Int? = null,
    )
    fun send(message: String): Flow<ChatEvent>
    fun hasConversation(): Boolean

    /** Nombre de tokens occupés dans la conversation en cours (null si inconnu ou sans conversation). */
    fun tokenCount(): Int?

    /** Interrompt la génération en cours, s'il y en a une. */
    fun cancel()

    /**
     * Génération ponctuelle sans outils (ex. une recette en JSON). Ferme la conversation en cours :
     * il faut rappeler newConversation ensuite.
     * [jsonSchema] : si fourni, la sortie est contrainte à ce schéma JSON.
     */
    suspend fun generateOnce(
        systemPrompt: String,
        prompt: String,
        jsonSchema: String? = null,
        maxOutputTokens: Int? = null,
    ): String
    fun close()
}

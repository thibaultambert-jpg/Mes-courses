package fr.mescourses.core.catalog

/**
 * Calcul des vecteurs sémantiques (256 dimensions, INT8) d'une saisie ou de fiches du catalogue. Implémenté côté
 * appli (modèle embarqué) et côté CI ; null quand le modèle n'est pas disponible.
 */
interface TextEmbedder {
    /** Le modèle est prêt à servir. */
    suspend fun available(): Boolean

    /** Vecteur d'une saisie de recherche (texte brut, mis en forme par [EmbeddingText.query]) ; null si indisponible. */
    suspend fun embedQuery(text: String): ByteArray?

    /** Vecteurs de textes de fiches (mis en forme par [EmbeddingText.passage]), dans l'ordre ; null si indisponible. */
    suspend fun embedPassages(texts: List<String>): List<ByteArray>?
}

/**
 * Textes donnés au modèle d'embeddings. Changer leur forme oblige à recalculer tous les vecteurs du catalogue :
 * augmenter alors [VERSION].
 */
object EmbeddingText {
    const val VERSION = 1

    fun passage(e: CatalogEntry): String = "task: search result | text: ${e.title}. Ingrédients : ${e.keys.joinToString(", ")}"

    fun query(text: String): String = "task: search query | text: ${text.trim()}"
}

// --- Vecteurs INT8 et fusion des classements ---
// Ajoutés par une tâche suivante dans ce fichier : Int8Vectors (encodage Base64, similarité),
// Int8VectorIndex (plus proches voisins) et ReciprocalRankFusion (fusion mots + vecteurs).

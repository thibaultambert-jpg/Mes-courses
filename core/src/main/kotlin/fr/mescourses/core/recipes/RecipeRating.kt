package fr.mescourses.core.recipes

/**
 * Note « pondérée » d'une recette, comparable d'un site à l'autre : moyenne bayésienne qui tire la note vers
 * [PRIOR] tant qu'il y a peu d'avis. Un 5/5 sur 2 avis (≈ 4,17) passe ainsi derrière un 4,7 sur 500 avis (≈ 4,69),
 * et une recette sans note vaut [PRIOR] : ni pénalisée, ni favorisée.
 */
object RecipeRating {
    /** Note supposée d'une recette dont on ne sait rien. */
    const val PRIOR = 4.0
    /** Poids de cette supposition, en nombre d'avis fictifs. */
    const val PRIOR_VOTES = 10

    fun score(rating: Double?, count: Int?): Double {
        if (rating == null) return PRIOR
        val n = (count ?: 1).coerceAtLeast(0)
        return (rating * n + PRIOR * PRIOR_VOTES) / (n + PRIOR_VOTES)
    }
}

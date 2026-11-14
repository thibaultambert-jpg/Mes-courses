package fr.mescourses.core.catalog

import kotlinx.serialization.Serializable

/** Repères nutritionnels d'une recette du catalogue (voir [CatalogEntry.nutritionTags]). */
@Serializable
enum class NutritionTag {
    /** Plat léger. */
    LIGHT,
    /** Riche en fibres (légumineuses, céréales complètes, légumes). */
    HIGH_FIBER,
    /** Source d'oméga-3 (poissons gras, noix, colza…). */
    OMEGA3,
    /** Plat réconfortant. */
    COMFORT,
}

package fr.mescourses.core.model

import kotlinx.serialization.Serializable

/** Un ingrédient, tel qu'écrit dans une recette, avec sa quantité analysée si possible. */
@Serializable
data class Ingredient(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    /** Texte d'origine, ex. « 200 g de farine ». */
    val raw: String = name,
)

@Serializable
data class Recipe(
    val id: Long = 0,
    val title: String,
    val servings: Int = 4,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val notes: String? = null,
)

enum class MealSlot(val label: String) {
    PETIT_DEJEUNER("Petit-déjeuner"),
    DEJEUNER("Déjeuner"),
    DINER("Dîner"),
    ;

    companion object {
        fun parse(value: String?): MealSlot {
            val v = value?.lowercase()?.stripAccents().orEmpty()
            return when {
                "petit" in v || "breakfast" in v -> PETIT_DEJEUNER
                "dej" in v || "midi" in v || "lunch" in v -> DEJEUNER
                else -> DINER
            }
        }
    }
}

/** Un repas planifié : une recette à une date (ISO yyyy-MM-dd) pour un nombre de personnes. */
@Serializable
data class PlannedMeal(
    val id: Long = 0,
    val date: String,
    val slot: MealSlot = MealSlot.DINER,
    val recipeId: Long,
    val recipeTitle: String = "",
    val servings: Int,
    /** Vrai une fois les ingrédients ajoutés à la liste de courses. */
    val addedToList: Boolean = false,
)

enum class ItemSource { MANUEL, RECETTE }

@Serializable
data class ShoppingItem(
    val id: Long = 0,
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val source: ItemSource = ItemSource.MANUEL,
    /** Recette(s) d'origine, pour l'affichage. */
    val origin: String? = null,
    val checked: Boolean = false,
)

/** Retire les accents (é → e, ç → c, œ → oe…). */
fun String.stripAccents(): String {
    val normalized = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
    return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE")
}

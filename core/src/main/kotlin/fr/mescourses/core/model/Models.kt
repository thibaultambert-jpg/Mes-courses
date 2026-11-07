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
    val status: RecipeStatus = RecipeStatus.HISTORIQUE,
    val timesCooked: Int = 0,
    /** Date ISO (yyyy-MM-dd) de la dernière validation dans un menu. */
    val lastCookedAt: String? = null,
    val description: String? = null,
    val notes: String? = null,
) {
    val favorite: Boolean get() = status == RecipeStatus.FAVORI
    val banned: Boolean get() = status == RecipeStatus.BANNI
}

/** Les trois listes du livre de recettes. */
enum class RecipeStatus(val label: String) {
    HISTORIQUE("Historique"),
    FAVORI("Favoris"),
    BANNI("Ne plus proposer"),
}

/** Convives d'un plat. Un enfant compte par défaut pour une demi-portion. */
@Serializable
data class Portions(val adults: Int, val children: Int = 0) {
    fun equivalent(childFactor: Double = DEFAULT_CHILD_FACTOR): Double = adults + children * childFactor
    fun label(): String = buildList {
        add("$adults adulte${if (adults > 1) "s" else ""}")
        if (children > 0) add("$children enfant${if (children > 1) "s" else ""}")
    }.joinToString(" · ")

    operator fun plus(other: Portions) = Portions(adults + other.adults, children + other.children)

    companion object {
        const val DEFAULT_CHILD_FACTOR = 0.5
    }
}

/** Un plat du menu en cours de construction (brouillon). */
@Serializable
data class MenuDish(
    val title: String,
    val description: String = "",
    val portions: Portions,
    /** Renseigné quand le plat existe déjà dans le livre (favori, historique) : pas besoin de le générer. */
    val recipeId: Long? = null,
)

/** Un plat du menu de la semaine validé, à cocher une fois cuisiné. */
@Serializable
data class PlannedMeal(
    val id: Long = 0,
    /** Date de validation du menu (ISO yyyy-MM-dd). */
    val date: String,
    val recipeId: Long,
    val recipeTitle: String = "",
    val portions: Portions,
    val cooked: Boolean = false,
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

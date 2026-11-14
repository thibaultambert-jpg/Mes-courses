package fr.mescourses.core.menu

import fr.mescourses.core.model.Portions
import kotlinx.serialization.Serializable

/** Temps passé en cuisine par repas (préparation + cuisson). */
enum class CookingTime(val label: String, val detail: String, val maxMinutes: Int?) {
    EXPRESS("Express", "30 min max", 30),
    NORMAL("Normal", "1 h max", 60),
    LONG("J'ai le temps", "sans limite", null),
}

/** Part de recettes du livre (favoris, déjà cuisinées) face aux découvertes des sites. */
enum class Novelty(val label: String, val cookbookShare: Double) {
    CLASSIQUES("Mes classiques", 0.6),
    EQUILIBRE("Équilibré", 0.3),
    DECOUVERTES("Découvertes", 0.0),
}

/** Type d'un plat, pour la composition de la semaine. */
enum class DishKind(val emoji: String, val label: String) {
    VEGE("🥦", "végé"),
    POISSON("🐟", "poisson"),
    VIANDE("🍗", "viande"),
}

/** Le formulaire « envies de la semaine » : tout ce qui guide la recherche et le choix des recettes. */
@Serializable
data class MenuWishes(
    val meals: Int,
    val portions: Portions,
    val time: CookingTime = CookingTime.NORMAL,
    /** Nombre de repas voulus par type ; le reste est libre. */
    val vegetarian: Int = 0,
    val fish: Int = 0,
    val meat: Int = 0,
    /** Ingrédients déjà à la maison, à utiliser en priorité. */
    val useUp: List<String> = emptyList(),
    val novelty: Novelty = Novelty.EQUILIBRE,
    /** Envies en texte libre, séparées par des virgules. */
    val wishes: String = "",
    /** Entrées et desserts en plus des plats ([meals]) : 0 = aucun. */
    val starters: Int = 0,
    val desserts: Int = 0,
) {
    fun quota(kind: DishKind): Int = when (kind) {
        DishKind.VEGE -> vegetarian
        DishKind.POISSON -> fish
        DishKind.VIANDE -> meat
    }

    val hasQuotas: Boolean get() = vegetarian + fish + meat > 0

    /** Garde la composition dans le nombre de repas (on rogne la viande, puis le poisson, puis le végé). */
    fun normalized(): MenuWishes {
        val m = meals.coerceIn(1, 21)
        val v = vegetarian.coerceIn(0, m)
        val f = fish.coerceIn(0, m - v)
        return copy(
            meals = m, vegetarian = v, fish = f, meat = meat.coerceIn(0, m - v - f),
            starters = starters.coerceIn(0, 21), desserts = desserts.coerceIn(0, 21),
        )
    }

    /** « 2 végé, 1 poisson » ; vide si la composition est libre. */
    fun compositionLabel(): String = DishKind.entries.filter { quota(it) > 0 }.joinToString(", ") { "${quota(it)} ${it.label}" }

    /** Envies libres, une par élément. */
    fun wishList(): List<String> = wishes.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

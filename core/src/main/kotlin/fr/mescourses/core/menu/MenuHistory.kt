package fr.mescourses.core.menu

import fr.mescourses.core.model.MenuHistoryEntry
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.shopping.ShoppingListMerger
import java.time.LocalDate

/**
 * Ce qu'on a mangé les semaines passées, pour varier : un plat n'est pas reproposé avant [REPEAT_WEEKS] semaines,
 * et les ingrédients principaux des deux dernières semaines pèsent contre une recette qui les reprend.
 * Le livre de recettes (date de dernière validation) complète l'historique des menus.
 */
class MenuHistory(entries: List<MenuHistoryEntry>, cookbook: List<Recipe>, private val today: LocalDate) {

    companion object {
        const val REPEAT_WEEKS = 4L
    }

    private fun since(weeks: Long) = today.minusWeeks(weeks).toString()

    private val recent = entries.filter { it.date >= since(REPEAT_WEEKS) && it.date <= today.toString() }
    private val recentCooked = cookbook.filter {
        it.status != RecipeStatus.BANNI && it.lastCookedAt != null && it.lastCookedAt >= since(REPEAT_WEEKS)
    }
    private val titleIndex = CookbookIndex(recent.map { Recipe(title = it.title) } + recentCooked)
    private val urls = (recent.mapNotNull { it.sourceUrl } + recentCooked.mapNotNull { it.sourceUrl }).map(RecipeSites::normalize).toSet()

    /** Poids de chaque ingrédient récemment mangé : 1 la semaine dernière, 0,5 celle d'avant. */
    private val weights: Map<String, Double> = buildMap {
        for (e in recent) {
            val w = when {
                e.date >= since(1) -> 1.0
                e.date >= since(2) -> 0.5
                else -> continue
            }
            e.mainIngredients.forEach { k -> put(k, maxOf(get(k) ?: 0.0, w)) }
        }
    }

    /** Titres des plats des dernières semaines (pour le prompt). */
    val recentTitles: List<String> = (recent.map { it.title } + recentCooked.map { it.title }).distinctBy { ShoppingListMerger.key(it) }

    /** Ingrédients principaux de la semaine dernière, les plus récents d'abord. */
    val lastWeekIngredients: List<String> = weights.filterValues { it >= 1.0 }.keys.toList()

    /** Le plat (même titre ou même adresse) a-t-il été au menu ces dernières semaines ? */
    fun eatenRecently(title: String, url: String?): Boolean =
        (url != null && RecipeSites.normalize(url) in urls) || titleIndex.find(title) != null

    /** Pénalité de répétition d'un plat d'après ses ingrédients (0 = rien de commun avec les semaines passées). */
    fun repetition(ingredientKeys: Collection<String>): Double = ingredientKeys.sumOf { weights[it] ?: 0.0 }

    /** Un légume ou produit de saison a-t-il déjà servi la semaine dernière ? (sert à faire tourner les recherches) */
    fun usedLastWeek(name: String): Boolean = IngredientOverlap.key(name).let { k -> weights.keys.any { it == k || it.startsWith("$k ") } }
}

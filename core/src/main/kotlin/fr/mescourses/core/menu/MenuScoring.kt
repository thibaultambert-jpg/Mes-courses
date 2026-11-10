package fr.mescourses.core.menu

import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.season.Seasonality
import fr.mescourses.core.shopping.Pantry
import fr.mescourses.core.shopping.ShoppingListMerger
import java.time.Month
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Type d'un plat (végé, poisson, viande) d'après son titre et ses ingrédients. */
object DishKinds {
    // « coque » (œuf à la coque) et « bar » (barre, bar à salade) prêtent à confusion dans un titre.
    private val fish = (RecipeFilter.POISSON.require + RecipeFilter.FRUITS_DE_MER.require - setOf("coque", "bar")).map(::word)
    private val meat = (RecipeFilter.VIANDE.require + RecipeFilter.VOLAILLE.require).map(::word)

    /** Un bouillon de volaille ou une sauce poisson ne font pas un plat de viande ou de poisson. */
    private val seasoning = Regex("""\b(bouillon|fond|sauce|cube|fumet|nuoc|arome)\b""")

    private fun word(w: String) = " " + ShoppingListMerger.key(w) + " "

    fun of(title: String, ingredients: List<String>): DishKind {
        val texts = (listOf(title) + ingredients).map { " " + ShoppingListMerger.key(it) + " " }.filterNot { seasoning.containsMatchIn(it) }
        return when {
            texts.any { t -> meat.any { it in t } } -> DishKind.VIANDE
            texts.any { t -> fish.any { it in t } } -> DishKind.POISSON
            else -> DishKind.VEGE
        }
    }
}

/** Ce que le compositeur retient d'un plat déjà au menu. */
data class Chosen(val keys: Set<String>, val kind: DishKind, val protein: String?, val fromCookbook: Boolean) {
    companion object {
        fun of(c: Candidate) = Chosen(c.keys, c.kind, c.protein, c.fromCookbook)

        fun of(d: MenuDish) = Chosen(
            keys = d.mainIngredients.map(IngredientOverlap::key).filter { it.isNotEmpty() }.toSet(),
            kind = DishKinds.of(d.title, d.mainIngredients),
            protein = PlateBalance.protein(listOf(d.title) + d.mainIngredients),
            fromCookbook = d.recipeId != null,
        )
    }
}

/**
 * Compose un menu sans LLM : à chaque tour, la candidate qui apporte le plus au menu en cours
 * (saison, composition demandée, ingrédients déjà prévus, variété). Sert de filet de sécurité quand le
 * modèle n'a pas répondu, à compléter son choix, et à trouver une « autre recette ».
 */
object MenuComposer {

    /** Points d'une candidate, compte tenu des plats déjà retenus. */
    fun score(c: Candidate, chosen: List<Chosen>, wishes: MenuWishes): Double {
        var s = c.base
        val counts = chosen.groupingBy { it.kind }.eachCount()
        val slotsLeft = wishes.meals - chosen.size
        if (wishes.hasQuotas) {
            val stillNeeded = DishKind.entries.sumOf { (wishes.quota(it) - (counts[it] ?: 0)).coerceAtLeast(0) }
            when {
                wishes.quota(c.kind) > (counts[c.kind] ?: 0) -> s += 8
                // Plus assez de places pour honorer la composition demandée : ce type attendra.
                stillNeeded >= slotsLeft -> s -= 100
            }
        } else {
            // Repères santé : poisson 1 à 2 fois, viande au plus un repas sur deux.
            val n = counts[c.kind] ?: 0
            val half = ceil(wishes.meals / 2.0).toInt()
            when (c.kind) {
                DishKind.POISSON -> if (n >= 2) s -= 4
                DishKind.VIANDE -> if (n >= half) s -= 4
                DishKind.VEGE -> if (n > half) s -= 2
            }
        }
        // Liste de courses : réutiliser ce qui est déjà prévu, acheter peu de produits nouveaux.
        val shared = c.keys.count { k -> chosen.any { k in it.keys } }
        s += min(shared, 4) * 1.5
        s -= (c.keys.size - shared) * 0.25
        // Variété : pas deux fois la même protéine, ni deux plats presque identiques.
        if (c.protein != null && chosen.any { it.protein == c.protein }) s -= 3
        if (chosen.any { similar(c.keys, it.keys) }) s -= 8
        // Dosage classiques / découvertes.
        val target = (wishes.meals * wishes.novelty.cookbookShare).roundToInt()
        if (c.fromCookbook) s += if (chosen.count { it.fromCookbook } < target) 5 else -5
        return s
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common) > 0.6
    }

    /** Complète [chosen] jusqu'au nombre de repas voulu avec les meilleures candidates de [pool]. */
    fun compose(pool: List<Candidate>, wishes: MenuWishes, chosen: List<Chosen> = emptyList(), exclude: Set<Candidate> = emptySet()): List<Candidate> {
        val picked = ArrayList<Candidate>()
        val state = chosen.toMutableList()
        val left = pool.filterNot { it in exclude }.toMutableList()
        while (state.size < wishes.meals && left.isNotEmpty()) {
            val best = left.maxBy { score(it, state, wishes) }
            left -= best
            picked += best
            state += Chosen.of(best)
        }
        return picked
    }

    /** Meilleure candidate pour une place du menu, les autres plats étant fixés. */
    fun replacement(pool: List<Candidate>, wishes: MenuWishes, others: List<Chosen>, exclude: Set<Candidate>): Candidate? =
        compose(pool, wishes.copy(meals = others.size + 1), others, exclude).firstOrNull()
}

/** Bilan d'un menu : saison, composition, taille de la liste. Affiché à l'écran et renvoyé au LLM après chaque outil. */
data class MenuInsights(
    /** Produits distincts à acheter (ingrédients principaux, hors placard). */
    val articles: Int,
    val seasonal: List<String>,
    /** Produit hors saison et numéro (à partir de 1) du plat qui l'utilise. */
    val outOfSeason: List<Pair<Int, String>>,
    val kinds: Map<DishKind, Int>,
) {
    companion object {
        fun of(dishes: List<MenuDish>, month: Month): MenuInsights {
            val seasonal = LinkedHashSet<String>()
            val out = ArrayList<Pair<Int, String>>()
            val keys = HashSet<String>()
            dishes.forEachIndexed { i, d ->
                val report = Seasonality.report(listOf(d.title) + d.mainIngredients, month)
                seasonal += report.seasonal
                report.outOfSeason.forEach { out += (i + 1) to it }
                d.mainIngredients.filterNot { Pantry.isStaple(it) }.map(IngredientOverlap::key).filterTo(keys) { it.isNotEmpty() }
            }
            return MenuInsights(keys.size, seasonal.toList(), out, dishes.groupingBy { DishKinds.of(it.title, it.mainIngredients) }.eachCount())
        }
    }

    fun kindsLabel(): String = DishKind.entries.filter { (kinds[it] ?: 0) > 0 }.joinToString(", ") { "${kinds[it]} ${it.label}" }

    /** Phrase pour le modèle : ce qui va, ce qu'il doit corriger. */
    fun summary(dishCount: Int, wishes: MenuWishes?): String = buildList {
        if (seasonal.isNotEmpty()) add("Produits de saison : ${seasonal.joinToString(", ")}.")
        if (outOfSeason.isNotEmpty()) {
            add("Hors saison : ${outOfSeason.joinToString(", ") { (n, name) -> "$name (plat $n)" }} — remplace ces plats si une candidate de saison convient.")
        }
        if (wishes != null) {
            if (dishCount != wishes.meals) add("Le menu doit compter ${wishes.meals} plats (il en a $dishCount).")
            val missing = DishKind.entries.filter { wishes.quota(it) > (kinds[it] ?: 0) }
            if (missing.isNotEmpty()) {
                add("Composition demandée : ${wishes.compositionLabel()} ; le menu a ${kindsLabel().ifEmpty { "0 plat" }} — il manque ${missing.joinToString(", ") { "${wishes.quota(it) - (kinds[it] ?: 0)} ${it.label}" }}.")
            }
        }
    }.joinToString(" ")
}

/** Aperçu de la liste de courses d'un menu, calculé sur les vraies recettes mises à l'échelle des convives. */
data class ShoppingPreview(
    /** Lignes de la liste une fois fusionnée. */
    val items: Int,
    /** Produits achetés à la pièce dont une partie restera (« ½ chou-fleur »). */
    val leftovers: List<String>,
) {
    companion object {
        fun of(recipes: List<Pair<Recipe, Double>>, pantry: List<String> = Pantry.DEFAULTS): ShoppingPreview {
            val all = recipes.flatMap { (r, portions) -> ShoppingListMerger.fromRecipe(r, portions, pantry) }
            // Pièces entières (sans unité) : la somme des recettes dit ce qui reste une fois le produit entamé.
            val leftovers = all.filter { it.unit == null && it.quantity != null && !ShoppingListMerger.isEgg(it.name) }
                .groupBy { ShoppingListMerger.key(it.name) }
                .mapNotNull { (_, group) ->
                    val total = group.sumOf { it.quantity ?: 0.0 }
                    val rest = ceil(total - 1e-9) - total
                    // Un reste d'un quart à trois quarts d'un produit qui ne sert qu'à un plat.
                    if (group.size == 1 && rest in 0.25..0.75 && total - floor(total) > 0) "${fraction(rest)} ${FoodName.clean(group.first().name).lowercase()}" else null
                }
            return ShoppingPreview(ShoppingListMerger.merge(all).size, leftovers)
        }

        private fun fraction(x: Double): String = when {
            x < 0.4 -> "⅓"
            x < 0.6 -> "½"
            else -> "⅔"
        }
    }
}

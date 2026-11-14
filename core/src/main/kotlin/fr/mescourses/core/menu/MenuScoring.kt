package fr.mescourses.core.menu

import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.NutritionTag
import fr.mescourses.core.catalog.NutritionTags
import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.ingredients.PairingGraph
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

/**
 * Forme d'un plat d'après son titre (pâtes, tarte salée, gratin…) : deux plats de même forme, ça passe ; trois, non.
 */
object DishForms {
    private val forms = linkedMapOf(
        "tarte" to listOf("tarte", "quiche", "tourte", "flamiche", "pissaladiere", "feuillete", "tatin", "cake"),
        "pizza" to listOf("pizza", "flammekueche", "calzone", "focaccia"),
        "pates" to RecipeFilter.PATES.require + listOf("carbonara", "bolognaise", "coquillette", "tortellini"),
        "gratin" to listOf("gratin", "parmentier", "tartiflette", "moussaka", "crumble"),
        "soupe" to listOf("soupe", "veloute", "potage", "bouillon", "minestrone", "gaspacho"),
        "salade" to listOf("salade", "taboule", "poke", "bowl", "buddha"),
        "riz" to listOf("risotto", "riz", "paella", "biryani", "pilaf"),
        "curry" to listOf("curry", "dahl", "dal", "tajine", "couscous"),
        "sandwich" to listOf("burger", "sandwich", "wrap", "croque", "tacos", "fajita", "kebab", "galette", "crepe"),
        "oeufs" to listOf("omelette", "frittata", "tortilla", "oeuf"),
    ).mapValues { (_, words) -> words.map { " " + ShoppingListMerger.key(it) + " " } }

    fun of(title: String): String? {
        val key = " " + ShoppingListMerger.key(title) + " "
        return forms.entries.firstOrNull { (_, words) -> words.any { it in key } }?.key
    }

    /** Plats de même forme acceptés dans un menu. */
    const val MAX = 2
}

/** Ce que le compositeur retient d'un plat déjà au menu. */
data class Chosen(
    val keys: Set<String>, val kind: DishKind, val protein: String?, val fromCookbook: Boolean,
    val form: String? = null, val site: String? = null,
    val tags: Set<NutritionTag> = emptySet(),
) {
    companion object {
        fun of(c: Candidate) = Chosen(c.keys, c.kind, c.protein, c.fromCookbook, c.form, c.siteName, c.tags)

        fun of(d: MenuDish) = Chosen(
            keys = d.mainIngredients.map(IngredientOverlap::key).filter { it.isNotEmpty() }.toSet(),
            kind = DishKinds.of(d.title, d.mainIngredients),
            protein = PlateBalance.protein(listOf(d.title) + d.mainIngredients),
            fromCookbook = d.recipeId != null,
            form = DishForms.of(d.title),
            site = d.sourceUrl?.let { fr.mescourses.core.recipes.RecipeSites.forUrl(it)?.name },
            tags = NutritionTags.of(
                d.title,
                d.mainIngredients,
                d.course ?: Course.of(emptyList(), d.title, d.mainIngredients).takeIf { it != Course.AUTRE } ?: Course.PLAT,
                d.totalMinutes,
            ),
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
    fun score(c: Candidate, chosen: List<Chosen>, wishes: MenuWishes, pairings: PairingGraph = PairingGraph.EMPTY): Double {
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
        // Viande et poisson : la même pièce sert à deux plats (un seul achat), pas à trois.
        if (c.protein != null) {
            val same = chosen.count { it.protein == c.protein }
            val bought = c.kind != DishKind.VEGE
            s += when {
                same == 0 -> 0.0
                same == 1 -> if (bought) 2.5 else -3.0
                else -> -8.0
            }
        }
        // Variété : pas deux plats presque identiques, au plus deux plats de même forme (pâtes, tarte, gratin…).
        if (chosen.any { similar(c.keys, it.keys) }) s -= 8
        if (c.form != null) {
            val same = chosen.count { it.form == c.form }
            if (same >= DishForms.MAX) s -= 100 else if (same == 1) s -= 5
        }
        // Les sites se partagent le menu : une recette d'un site déjà très présent passe après.
        if (c.site != null) s -= chosen.count { it.site == c.site } * 1.5
        // Dosage classiques / découvertes.
        val target = (wishes.meals * wishes.novelty.cookbookShare).roundToInt()
        if (c.fromCookbook) s += if (chosen.count { it.fromCookbook } < target) 5 else -5
        // Accords d'ingrédients et équilibre nutritionnel du menu.
        val chosenKeys = chosen.flatMapTo(HashSet()) { it.keys }
        s += 3.0 * pairings.affinityScore(c.keys + chosenKeys)
        val comfortCount = chosen.count { NutritionTag.COMFORT in it.tags }
        if (NutritionTag.COMFORT in c.tags && comfortCount >= 1) s -= 3.0 * comfortCount
        if (NutritionTag.LIGHT in c.tags && comfortCount >= 1) s += 2.0
        if (NutritionTag.OMEGA3 in c.tags && chosen.none { NutritionTag.OMEGA3 in it.tags || it.kind == DishKind.POISSON }) s += 2.0
        return s
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common) > 0.6
    }

    /** Complète [chosen] jusqu'au nombre de repas voulu avec les meilleures candidates de [pool]. */
    fun compose(
        pool: List<Candidate>,
        wishes: MenuWishes,
        chosen: List<Chosen> = emptyList(),
        exclude: Set<Candidate> = emptySet(),
        pairings: PairingGraph = PairingGraph.EMPTY,
    ): List<Candidate> {
        val picked = ArrayList<Candidate>()
        val state = chosen.toMutableList()
        val left = pool.filterNot { it in exclude }.toMutableList()
        while (state.size < wishes.meals && left.isNotEmpty()) {
            val best = left.maxBy { score(it, state, wishes, pairings) }
            left -= best
            picked += best
            state += Chosen.of(best)
        }
        return picked
    }

    /** Un échange qui rend la liste de courses plus courte : le plat [index] cède sa place à [candidate]. */
    data class Swap(val index: Int, val candidate: Candidate, val sharedBefore: Int, val sharedAfter: Int)

    private fun shared(keys: Set<String>, others: List<Chosen>) = keys.count { k -> others.any { k in it.keys } }

    /**
     * « Mieux mutualiser », sans LLM : cherche le plat à remplacer par une candidate qui partage davantage
     * d'ingrédients avec le reste du menu, sans dégrader le menu (saison, composition, variété).
     * [dishes] : chaque plat du menu avec sa candidate d'origine si elle est connue. Null = rien à gagner.
     */
    fun improveSharing(
        pool: List<Candidate>,
        wishes: MenuWishes,
        dishes: List<Pair<Chosen, Candidate?>>,
        exclude: Set<Candidate> = emptySet(),
        pairings: PairingGraph = PairingGraph.EMPTY,
    ): Swap? {
        val w = wishes.copy(meals = dishes.size)
        val taken = dishes.mapNotNull { it.second }.toSet() + exclude
        return dishes.indices.mapNotNull { i ->
            val others = dishes.filterIndexed { j, _ -> j != i }.map { it.first }
            val (current, origin) = dishes[i]
            val before = shared(current.keys, others)
            val best = pool.filterNot { it in taken }
                .filter { shared(it.keys, others) > before && (origin == null || score(it, others, w, pairings) >= score(origin, others, w, pairings)) }
                .maxWithOrNull(compareBy<Candidate> { shared(it.keys, others) }.thenBy { score(it, others, w, pairings) })
                ?: return@mapNotNull null
            Swap(i, best, before, shared(best.keys, others))
        }.maxByOrNull { it.sharedAfter - it.sharedBefore }
    }

    /** Meilleure candidate pour une place du menu, les autres plats étant fixés. */
    fun replacement(
        pool: List<Candidate>,
        wishes: MenuWishes,
        others: List<Chosen>,
        exclude: Set<Candidate>,
        pairings: PairingGraph = PairingGraph.EMPTY,
    ): Candidate? =
        compose(pool, wishes.copy(meals = others.size + 1), others, exclude, pairings).firstOrNull()
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

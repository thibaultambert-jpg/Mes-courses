package fr.mescourses.core.catalog

import fr.mescourses.core.menu.DishForms
import fr.mescourses.core.recipes.Words
import fr.mescourses.core.shopping.ShoppingListMerger

/**
 * Repères nutritionnels déterministes d'une fiche du catalogue, d'après son titre, ses clés d'ingrédients, son type
 * de plat et sa durée. Tous les mots passent par [ShoppingListMerger.key] et se cherchent en mot entier.
 */
object NutritionTags {
    /** Ingrédients « riches » (gras, laitiers, charcuterie, pâtes à tarte). */
    private val riches = listOf(
        "creme", "creme fraiche", "beurre", "lardon", "fromage", "mascarpone", "mayonnaise", "pate feuilletee",
        "pate brisee", "huile de friture", "chantilly", "gruyere", "emmental", "comte", "reblochon", "raclette",
    )

    /** Mots du titre qui annoncent une cuisine légère. */
    private val motsLegers = listOf("leger", "light", "minceur", "vapeur", "poche", "papillote", "grille", "detox")

    /** Légumineuses : celles des filtres de recettes, plus quelques variantes. */
    private val legumineuses = Words.legumineuses + listOf("haricot noir", "feve", "lentille corail", "pois casse")

    /** Aliments riches en fibres : il en faut deux (une légumineuse suffit). */
    private val fibres = listOf(
        "brocoli", "chou", "chou fleur", "epinard", "poireau", "artichaut", "petit poi", "patate douce", "courge",
        "betterave", "quinoa", "boulgour", "flocon d avoine", "avoine", "son d avoine", "pain complet", "riz complet",
        "pate complete", "pomme", "poire", "framboise", "figue", "pruneau", "amande", "noix", "graine de chia",
        "graine de lin",
    )

    /** Sources d'oméga-3 : poissons gras, noix, graines et huiles. */
    private val omega3 = listOf(
        "saumon", "maquereau", "sardine", "hareng", "truite", "thon", "anchois", "noix", "graine de lin",
        "huile de colza", "huile de noix", "graine de chia",
    )

    /** Formes de plat ([DishForms]) réconfortantes. */
    private val formesReconfortantes = setOf("gratin", "pates", "pizza", "sandwich")

    /** Mots du titre qui annoncent un plat réconfortant. */
    private val platsReconfortants = listOf(
        "tartiflette", "raclette", "fondue", "lasagne", "parmentier", "pot au feu", "blanquette", "bourguignon",
        "carbonara", "croque", "risotto", "puree", "cassoulet", "chili", "brownie", "fondant", "crumble", "mac and cheese",
    )

    /** Nombre de [words] présents en mot entier dans [text] (texte entouré d'espaces, déjà normalisé). */
    private fun found(text: String, words: List<String>): Int =
        words.count { w -> ShoppingListMerger.key(w).let { it.isNotEmpty() && text.contains(" $it ") } }

    fun of(title: String, keys: List<String>, course: Course, minutes: Int?): Set<NutritionTag> {
        // Double espace entre deux clés : un mot cherché ne peut pas enjamber deux ingrédients.
        val ing = " " + keys.joinToString("  ") { ShoppingListMerger.key(it) } + " "
        val t = " " + ShoppingListMerger.key(title) + " "
        val nbRiches = found(ing, riches)
        val tags = linkedSetOf<NutritionTag>()

        val legerParCourse = course == Course.SOUPE || course == Course.SALADE
        if ((legerParCourse || found(t, motsLegers) > 0) && course != Course.DESSERT && nbRiches < 2) {
            tags += NutritionTag.LIGHT
        }
        if (found(ing, legumineuses) >= 1 || found(ing, fibres) >= 2) tags += NutritionTag.HIGH_FIBER
        if (found(ing, omega3) >= 1) tags += NutritionTag.OMEGA3
        if (DishForms.of(title) in formesReconfortantes || found(t, platsReconfortants) > 0 || nbRiches >= 2 ||
            (course == Course.PLAT && (minutes ?: 0) >= 90)
        ) {
            tags += NutritionTag.COMFORT
        }
        return tags
    }
}

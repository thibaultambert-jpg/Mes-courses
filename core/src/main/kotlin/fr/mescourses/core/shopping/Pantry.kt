package fr.mescourses.core.shopping

import fr.mescourses.core.ingredients.FoodName

/**
 * Produits « de placard » qu'on a toujours chez soi : ils restent dans les recettes pour cuisiner,
 * mais n'entrent jamais dans la liste de courses (sel, poivre, huile, vinaigre, condiments, beurre, lait).
 */
object Pantry {

    /** Premier mot (clé normalisée) qui désigne un produit de placard. */
    private val staples = setOf(
        "sel", "poivre", "huile", "vinaigre", "vinaigrette", "ketchup", "mayonnaise", "mayo", "moutarde",
        "tabasco", "sauce", "beurre", "lait", "eau",
    )

    /** Vrais ingrédients de cuisine malgré leur premier mot. */
    private val keep = listOf(
        "lait de coco", "beurre de cacahuete", "sauce tomate", "eau de fleur", "lait d amande", "lait d avoine",
    )

    fun isStaple(name: String): Boolean {
        val k = ShoppingListMerger.key(FoodName.clean(name))
        if (k.isEmpty() || keep.any { k.startsWith(it) }) return false
        if (k == "fleur de sel") return true
        return k.substringBefore(' ') in staples
    }
}

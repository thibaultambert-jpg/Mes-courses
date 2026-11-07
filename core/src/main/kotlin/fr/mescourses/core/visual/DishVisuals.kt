package fr.mescourses.core.visual

import fr.mescourses.core.shopping.ShoppingListMerger

/** Illustration d'un plat sans photo : un emoji et une catégorie (qui fixe la couleur du dégradé). */
object DishVisuals {

    enum class Category { LEGUMES, POISSON, VIANDE, VEGETARIEN, SOUPE, DESSERT, AUTRE }

    data class Visual(val emoji: String, val category: Category)

    // Ordre important : le premier mot-clé trouvé dans le titre l'emporte.
    private val byTitle: List<Triple<String, String, Category>> = listOf(
        Triple("veloute", "🍲", Category.SOUPE), Triple("soupe", "🍲", Category.SOUPE), Triple("potage", "🍲", Category.SOUPE),
        Triple("bouillon", "🍜", Category.SOUPE), Triple("ramen", "🍜", Category.SOUPE), Triple("pho", "🍜", Category.SOUPE),
        Triple("tarte", "🥧", Category.LEGUMES), Triple("quiche", "🥧", Category.LEGUMES), Triple("pizza", "🍕", Category.AUTRE),
        Triple("curry", "🍛", Category.VEGETARIEN), Triple("dahl", "🍛", Category.VEGETARIEN), Triple("dal", "🍛", Category.VEGETARIEN),
        Triple("salade", "🥗", Category.LEGUMES), Triple("bowl", "🥗", Category.LEGUMES), Triple("buddha", "🥗", Category.LEGUMES),
        Triple("pate", "🍝", Category.AUTRE), Triple("spaghetti", "🍝", Category.AUTRE), Triple("lasagne", "🍝", Category.AUTRE),
        Triple("tagliatelle", "🍝", Category.AUTRE),
        Triple("gratin", "🧀", Category.LEGUMES), Triple("raclette", "🧀", Category.AUTRE), Triple("tartiflette", "🧀", Category.AUTRE),
        Triple("omelette", "🍳", Category.VEGETARIEN), Triple("oeuf", "🍳", Category.VEGETARIEN), Triple("crepe", "🥞", Category.AUTRE),
        Triple("galette", "🥞", Category.AUTRE), Triple("burger", "🍔", Category.VIANDE), Triple("taco", "🌮", Category.AUTRE),
        Triple("wrap", "🌯", Category.AUTRE), Triple("burrito", "🌯", Category.AUTRE), Triple("sandwich", "🥪", Category.AUTRE),
        Triple("saumon", "🐟", Category.POISSON), Triple("cabillaud", "🐟", Category.POISSON), Triple("poisson", "🐟", Category.POISSON),
        Triple("colin", "🐟", Category.POISSON), Triple("thon", "🐟", Category.POISSON), Triple("truite", "🐟", Category.POISSON),
        Triple("crevette", "🦐", Category.POISSON), Triple("moule", "🦪", Category.POISSON),
        Triple("poulet", "🍗", Category.VIANDE), Triple("dinde", "🍗", Category.VIANDE), Triple("canard", "🍗", Category.VIANDE),
        Triple("boeuf", "🥩", Category.VIANDE), Triple("steak", "🥩", Category.VIANDE), Triple("veau", "🥩", Category.VIANDE),
        Triple("agneau", "🥩", Category.VIANDE), Triple("porc", "🥓", Category.VIANDE), Triple("saucisse", "🌭", Category.VIANDE),
        Triple("risotto", "🍚", Category.AUTRE), Triple("riz", "🍚", Category.AUTRE),
        Triple("tofu", "🥢", Category.VEGETARIEN), Triple("wok", "🥢", Category.LEGUMES), Triple("nouille", "🍜", Category.AUTRE),
        Triple("couscous", "🫕", Category.AUTRE), Triple("tajine", "🫕", Category.AUTRE), Triple("pot au feu", "🫕", Category.VIANDE),
        Triple("chili", "🌶", Category.VIANDE), Triple("ratatouille", "🍆", Category.LEGUMES), Triple("lentille", "🫘", Category.VEGETARIEN),
        Triple("pois chiche", "🫘", Category.VEGETARIEN), Triple("falafel", "🧆", Category.VEGETARIEN),
        Triple("courge", "🎃", Category.LEGUMES), Triple("potiron", "🎃", Category.LEGUMES), Triple("potimarron", "🎃", Category.LEGUMES),
        Triple("champignon", "🍄", Category.LEGUMES), Triple("poireau", "🥬", Category.LEGUMES), Triple("chou", "🥬", Category.LEGUMES),
        Triple("brocoli", "🥦", Category.LEGUMES), Triple("carotte", "🥕", Category.LEGUMES), Triple("tomate", "🍅", Category.LEGUMES),
        Triple("aubergine", "🍆", Category.LEGUMES), Triple("pomme de terre", "🥔", Category.LEGUMES), Triple("parmentier", "🥔", Category.VIANDE),
        Triple("gateau", "🍰", Category.DESSERT), Triple("crumble", "🥧", Category.DESSERT), Triple("compote", "🍎", Category.DESSERT),
        Triple("mousse", "🍫", Category.DESSERT), Triple("clafoutis", "🍒", Category.DESSERT),
    )

    private fun word(kw: String) = " " + ShoppingListMerger.key(kw) + " "

    fun of(title: String, ingredients: List<String> = emptyList()): Visual {
        val t = " " + ShoppingListMerger.key(title) + " "
        byTitle.firstOrNull { (kw, _, _) -> word(kw) in t }?.let { return Visual(it.second, it.third) }
        val ing = " " + ingredients.joinToString(" ") { ShoppingListMerger.key(it) } + " "
        byTitle.firstOrNull { (kw, _, _) -> word(kw) in ing }?.let { return Visual(it.second, it.third) }
        return Visual("🍽", Category.AUTRE)
    }
}

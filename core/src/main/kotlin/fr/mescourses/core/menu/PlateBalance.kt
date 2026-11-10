package fr.mescourses.core.menu

import fr.mescourses.core.shopping.ShoppingListMerger

/**
 * Assiette équilibrée : chaque plat doit contenir une protéine, des légumes et un féculent.
 * Classification par mots-clés (clés normalisées : minuscules, sans accents, au singulier).
 */
object PlateBalance {

    enum class Component(val emoji: String, val label: String, val missingLabel: String) {
        PROTEINE("🥩", "Protéine", "une protéine"),
        LEGUMES("🥦", "Légumes", "des légumes"),
        FECULENT("🍚", "Féculent", "un féculent"),
    }

    private val proteins = listOf(
        "poulet", "dinde", "boeuf", "veau", "porc", "agneau", "canard", "lapin", "jambon", "lardon", "saucisse",
        "viande", "steak", "escalope", "roti", "magret", "poisson", "saumon", "cabillaud", "colin", "merlu", "lieu",
        "thon", "sardine", "maquereau", "truite", "dorade", "bar commun", "crevette", "moule", "noix de saint jacque",
        "oeuf", "omelette", "lentille", "pois chiche", "haricot rouge", "haricot blanc", "haricot noir", "flageolet",
        "tofu", "tempeh", "seitan", "edamame", "fromage", "comte", "parmesan", "chevre", "feta", "mozzarella",
        "ricotta", "emmental", "gruyere", "reblochon", "halloumi", "yaourt", "skyr", "fromage blanc", "dahl", "falafel",
    )

    private val starches = listOf(
        "riz", "pate", "spaghetti", "tagliatelle", "penne", "coquillette", "fusilli", "nouille", "lasagne", "gnocchi",
        "semoule", "couscous", "boulgour", "quinoa", "pomme de terre", "patate douce", "puree", "pain", "baguette",
        "galette", "sarrasin", "polenta", "orge", "epeautre", "ble", "millet", "tortilla", "wrap", "pate brisee",
        "pate feuilletee", "pizza", "crepe", "chataigne", "frite", "risotto", "tarte", "quiche", "parmentier", "gratin dauphinois",
    )

    private val vegetables = listOf(
        "legume", "carotte", "courgette", "poireau", "chou", "epinard", "tomate", "brocoli", "haricot vert", "petit poi",
        "poivron", "aubergine", "champignon", "salade", "laitue", "potiron", "potimarron", "butternut", "courge", "navet",
        "panais", "betterave", "fenouil", "celeri", "endive", "blette", "radis", "concombre", "artichaut", "asperge",
        "mache", "roquette", "cresson", "topinambour", "salsifi", "feve", "petits poi", "ratatouille", "poelee", "veloute",
        "soupe", "jardiniere", "crudite",
    )

    private val keys = mutableMapOf<String, String>()
    private fun k(kw: String) = keys.getOrPut(kw) { " " + ShoppingListMerger.key(kw) + " " }

    /** Mot-clé présent comme mot entier (« ble » ne doit pas trouver « blette »). */
    private fun matches(text: String, keywords: List<String>): Boolean {
        val t = " " + ShoppingListMerger.key(text) + " "
        return keywords.any { k(it) in t }
    }

    /** Composantes présentes dans un ensemble de textes (titre, description, ingrédients). */
    fun components(texts: List<String>): Set<Component> = buildSet {
        for (t in texts) {
            if (matches(t, proteins)) add(Component.PROTEINE)
            if (matches(t, vegetables)) add(Component.LEGUMES)
            if (matches(t, starches)) add(Component.FECULENT)
        }
    }

    /** Protéine principale d'un plat (premier mot-clé trouvé, titre d'abord), pour varier d'un repas à l'autre. */
    fun protein(texts: List<String>): String? {
        for (t in texts) {
            val key = " " + ShoppingListMerger.key(t) + " "
            proteins.firstOrNull { k(it) in key }?.let { return it }
        }
        return null
    }

    fun missing(texts: List<String>): List<Component> = Component.entries - components(texts)

    /** Bilan d'équilibre des plats d'un menu (null si on ne connaît pas leurs ingrédients). */
    fun menuSummary(dishes: List<fr.mescourses.core.model.MenuDish>): String? {
        val problems = dishes.mapIndexedNotNull { i, d ->
            if (d.mainIngredients.isEmpty() && d.recipeId != null) return@mapIndexedNotNull null
            val miss = missing(listOf(d.title, d.description) + d.mainIngredients)
            if (miss.isEmpty()) null else "plat ${i + 1} sans ${miss.joinToString(" ni ") { it.missingLabel.substringAfter(' ') }}"
        }
        if (problems.isEmpty()) return null
        return "Équilibre à corriger : ${problems.joinToString(", ")}. Chaque plat doit avoir une protéine, des légumes et un féculent : ajoute l'accompagnement manquant avec replace_dish."
    }
}

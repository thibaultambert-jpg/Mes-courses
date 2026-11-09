package fr.mescourses.core.shopping

import fr.mescourses.core.ingredients.FoodName

/**
 * Produits « de placard » qu'on a toujours chez soi : ils restent dans les recettes pour cuisiner,
 * mais n'entrent jamais dans la liste de courses. La liste est modifiable dans les Réglages
 * (« Toujours dans mon placard ») ; certaines entrées regroupent plusieurs produits (Épices, Herbes séchées…).
 */
object Pantry {

    /** Entrées proposées par défaut, dans l'ordre d'affichage. */
    val DEFAULTS: List<String> = listOf(
        "Sel", "Poivre", "Huile", "Vinaigre", "Farine", "Sucre", "Beurre", "Lait", "Eau",
        "Épices", "Herbes séchées", "Condiments", "Levure", "Fécule", "Bouillon", "Concentré de tomate", "Miel",
    )

    /** Produits couverts par une entrée regroupée (clés normalisées). */
    private val groups: Map<String, List<String>> = mapOf(
        "sel" to listOf("sel", "fleur de sel", "gros sel"),
        "poivre" to listOf("poivre", "baie rose"),
        "sucre" to listOf("sucre", "cassonade", "vergeoise", "sucre glace", "sucre vanille", "sucre vanille"),
        "epice" to listOf(
            "epice", "cumin", "curry", "paprika", "cannelle", "muscade", "noix de muscade", "piment", "curcuma",
            "gingembre moulu", "quatre epice", "4 epice", "ras el hanout", "coriandre moulue", "clou de girofle",
            "cardamome", "safran", "piment d espelette", "garam masala", "chili", "piment de cayenne", "cayenne",
            "5 baie", "cinq baie", "melange 5 baie", "epice a couscous", "epice couscous", "tandoori", "colombo",
            "zaatar", "za atar", "sumac", "fenugrec", "anis", "anis etoile", "badiane", "graine de fenouil",
            "graine de coriandre", "graine de moutarde", "graine de cumin", "graine de carvi", "carvi", "cumin en poudre",
            "gingembre en poudre", "gingembre moulu", "ail en poudre", "oignon en poudre", "herbe a tajine", "epice a tajine",
            "mélange d epice", "melange d epice", "piment en poudre", "piment moulu", "coriandre en poudre", "cannelle en poudre",
            "vanille en poudre", "poivre de cayenne", "poudre de piment", "curry en poudre", "massale", "berbere", "harissa en poudre",
        ),
        "herbe sechee" to listOf("herbe de provence", "thym", "laurier", "origan", "romarin seche", "feuille de laurier", "bouquet garni"),
        "condiment" to listOf("moutarde", "ketchup", "mayonnaise", "mayo", "tabasco", "sauce soja", "sauce", "vinaigrette", "worcestershire"),
        "levure" to listOf("levure", "bicarbonate", "poudre a lever"),
        "fecule" to listOf("fecule", "maizena", "amidon"),
        "bouillon" to listOf("bouillon", "cube de bouillon", "fond de volaille", "fond de veau"),
        "eau" to listOf("eau", "glacon"),
    )

    /** Vrais ingrédients de cuisine malgré leur premier mot. */
    private val keep = listOf(
        "lait de coco", "beurre de cacahuete", "sauce tomate", "eau de fleur", "lait d amande", "lait d avoine",
        "lait de soja", "sucre de coco", "piment frais", "poivron", "sauce bechamel",
    )

    private val keepKeys by lazy { keep.map(ShoppingListMerger::key) }

    /** Clés couvertes par une liste d'entrées (une entrée inconnue est prise telle quelle : « Riz » → riz). */
    fun keys(entries: List<String>): List<String> = entries.flatMap { e ->
        val k = ShoppingListMerger.key(e)
        groups[k]?.map(ShoppingListMerger::key) ?: listOf(k)
    }.filter { it.isNotBlank() }.distinct()

    fun isStaple(name: String, entries: List<String> = DEFAULTS): Boolean {
        val k = ShoppingListMerger.key(FoodName.clean(name))
        // Exceptions vérifiées aussi sur le nom brut : « piment frais » perd « frais » au nettoyage.
        val raw = ShoppingListMerger.key(name)
        if (k.isEmpty() || keepKeys.any { k.startsWith(it) || raw.startsWith(it) }) return false
        // Nom brut aussi : « gingembre en poudre » devient « gingembre » au nettoyage (frais, à acheter).
        return keys(entries).any { w -> k == w || k.startsWith("$w ") || raw == w || raw.startsWith("$w ") }
    }
}

package fr.mescourses.core.shopping

/** Rayons du magasin, dans l'ordre d'un parcours habituel. */
enum class Aisle(val label: String, val emoji: String) {
    FRUITS_LEGUMES("Fruits & légumes", "🥕"),
    BOULANGERIE("Boulangerie", "🥖"),
    BOUCHERIE("Boucherie", "🥩"),
    POISSONNERIE("Poissonnerie", "🐟"),
    CREMERIE("Crèmerie & œufs", "🧀"),
    EPICERIE("Épicerie", "🥫"),
    BOISSONS("Boissons", "🧃"),
    MAISON("Hygiène & maison", "🧴"),
    AUTRES("Autres", "📦"),
    ;

    companion object {
        private val keywords: List<Pair<Aisle, List<String>>> = listOf(
            MAISON to listOf("lessive", "papier toilette", "essuie tout", "savon", "shampo", "dentifrice", "eponge", "liquide vaisselle", "sac poubelle", "couche", "mouchoir", "gel douche", "deodorant"),
            BOISSONS to listOf("jus", "eau gazeuse", "eau minerale", "biere", "vin", "sirop", "soda", "cafe", "the"),
            POISSONNERIE to listOf("saumon", "cabillaud", "colin", "merlu", "lieu", "thon frais", "sardine", "maquereau", "truite", "dorade", "crevette", "moule", "poisson", "saint jacque"),
            BOUCHERIE to listOf("poulet", "dinde", "boeuf", "veau", "porc", "agneau", "canard", "lapin", "jambon", "lardon", "saucisse", "viande", "steak", "escalope", "magret", "chorizo", "bacon"),
            CREMERIE to listOf("lait", "creme", "beurre", "fromage", "comte", "parmesan", "chevre", "feta", "mozzarella", "ricotta", "emmental", "gruyere", "yaourt", "skyr", "oeuf", "mascarpone", "reblochon", "tofu"),
            BOULANGERIE to listOf("pain", "baguette", "brioche", "pate brisee", "pate feuilletee", "pate a pizza", "tortilla", "wrap"),
            FRUITS_LEGUMES to listOf(
                "carotte", "courgette", "poireau", "chou", "epinard", "tomate", "brocoli", "haricot vert", "poivron", "aubergine",
                "champignon", "salade", "laitue", "potiron", "potimarron", "butternut", "courge", "navet", "panais", "betterave",
                "fenouil", "celeri", "endive", "blette", "radis", "concombre", "artichaut", "asperge", "mache", "roquette",
                "pomme de terre", "patate douce", "oignon", "echalote", "ail", "persil", "coriandre", "basilic", "ciboulette",
                "menthe", "thym", "romarin", "gingembre", "citron", "orange", "pomme", "poire", "banane", "kiwi", "fraise",
                "framboise", "raisin", "clementine", "avocat", "mangue", "abricot", "peche", "prune", "cerise", "melon", "legume", "fruit",
            ),
            EPICERIE to listOf(
                "lait de coco", "riz", "pate", "spaghetti", "farine", "sucre", "sel", "poivre", "huile", "vinaigre", "lentille", "pois chiche",
                "haricot", "semoule", "couscous", "boulgour", "quinoa", "conserve", "bouillon", "epice", "cumin", "curry",
                "paprika", "moutarde", "miel", "chocolat", "levure", "noix", "amande", "noisette", "olive",
                "sauce", "concentre", "coulis", "flocon", "cereale", "biscuit", "confiture",
            ),
        )

        /**
         * Le mot-clé trouvé le plus tôt dans le nom décide, le plus long en cas d'égalité :
         * « lait de coco » → épicerie (pas crèmerie), « crème de marrons » → crèmerie… à corriger à la main au besoin.
         */
        fun of(name: String): Aisle {
            val k = " " + ShoppingListMerger.key(name) + " "
            var best: Triple<Int, Int, Aisle>? = null
            for ((aisle, kws) in keywords) for (kw in kws) {
                val w = " " + ShoppingListMerger.key(kw) + " "
                val pos = k.indexOf(w)
                if (pos < 0) continue
                val candidate = Triple(pos, -w.length, aisle)
                if (best == null || compareValuesBy(candidate, best, { it.first }, { it.second }) < 0) best = candidate
            }
            return best?.third ?: AUTRES
        }
    }
}

package fr.mescourses.core.recipes

import fr.mescourses.core.shopping.ShoppingListMerger

/** Groupes de filtres de l'écran Découvrir. `single` = un seul choix possible dans le groupe. */
enum class FilterGroup(val label: String, val single: Boolean) {
    TYPE("Type de plat", true),
    PRINCIPAL("Ingrédient principal", false),
    REGIME("Régime", false),
    TEMPS("Temps", true),
    ENVIE("Envie", false),
}

/** Mots-clés (titres de recettes) par catégorie d'ingrédient, partagés par les filtres et les pastilles. */
private object Words {
    val viande = listOf(
        "boeuf", "veau", "porc", "agneau", "steak", "entrecote", "bourguignon", "saucisse", "jambon", "lardon", "chorizo",
        "viande", "filet mignon", "hachis", "boulette", "merguez", "gigot", "blanquette", "kefta",
        "bacon", "carpaccio", "tartare de boeuf", "pot au feu", "osso buco", "chili con carne", "bolognaise",
    )
    val volaille = listOf("poulet", "dinde", "canard", "pintade", "volaille", "caille", "coq", "magret", "aiguillette")
    val poisson = listOf(
        "poisson", "saumon", "cabillaud", "thon", "truite", "sardine", "maquereau", "colin", "merlu", "lieu", "dorade",
        "daurade", "bar", "sole", "haddock", "morue", "lotte", "eglefin", "anchois", "brandade", "rouget", "flétan", "fletan",
    )
    val fruitsDeMer = listOf(
        "crevette", "moule", "saint jacque", "noix de saint", "calamar", "encornet", "poulpe", "gamba", "homard",
        "langoustine", "crabe", "huitre", "fruits de mer", "fruit de mer", "seiche", "palourde", "coque",
    )
    val oeufs = listOf("oeuf", "omelette", "quiche", "frittata", "oeufs", "tortilla")
    val legumes = listOf(
        "legume", "courgette", "aubergine", "tomate", "carotte", "potiron", "courge", "potimarron", "chou", "brocoli",
        "poireau", "epinard", "ratatouille", "champignon", "poivron", "haricot vert", "butternut", "panais", "betterave",
        "fenouil", "asperge", "artichaut", "celeri", "petit pois", "endive", "navet", "patate douce",
    )
    val pates = listOf(
        "pate", "spaghetti", "tagliatelle", "penne", "lasagne", "ravioli", "gnocchi", "macaroni", "linguine", "nouille",
        "fusilli", "farfalle", "cannelloni", "conchiglie", "orzo", "udon", "ramen",
    )
    val riz = listOf("riz", "risotto", "quinoa", "boulgour", "semoule", "couscous", "epeautre", "paella", "biryani", "polenta")
    val legumineuses = listOf("lentille", "pois chiche", "haricot rouge", "haricot blanc", "feve", "houmous", "dal", "falafel", "flageolet")
    val fromage = listOf(
        "fromage", "gratin", "raclette", "tartiflette", "chevre", "comte", "mozzarella", "feta", "parmesan", "camembert",
        "reblochon", "roquefort", "burrata", "ricotta", "mascarpone", "brie", "fondue", "croque", "cheese",
    )
    val laitier = fromage + listOf("lait", "creme", "beurre", "yaourt", "yogourt", "flan", "panna cotta", "bechamel", "chantilly")
    val gluten = listOf(
        "pate", "spaghetti", "tagliatelle", "lasagne", "ravioli", "pain", "pizza", "tarte", "quiche", "crepe", "gaufre",
        "couscous", "semoule", "boulgour", "brioche", "croque", "burger", "sandwich", "biscuit", "cookie", "cake", "muffin",
        "gnocchi", "nouille", "feuillete", "croissant", "panure", "pane", "beignet",
    )
    val porcEtAlcool = listOf(
        "porc", "cochon", "lardon", "jambon", "bacon", "chorizo", "saucisson", "rillette", "andouille", "pancetta",
        "coppa", "speck", "guanciale", "rosette", "boudin", "travers", "carbonara", "tartiflette",
        "vin", "biere", "rhum", "cognac", "calvados", "kirsch", "whisky", "vodka", "liqueur", "porto", "champagne",
        "cidre", "grand marnier", "amaretto", "marsala", "sangria", "flambe", "bourguignon", "coq au vin", "sauternes",
    )
}

/**
 * Filtres de recherche de recettes. `query` = mots ajoutés à la recherche des sites ; `require` = le titre doit
 * contenir l'un de ces mots ; `exclude` = le titre ne doit en contenir aucun ; `maxMinutes` = durée maximale (si connue).
 */
enum class RecipeFilter(
    val group: FilterGroup,
    val label: String,
    val emoji: String,
    val query: String?,
    val require: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val maxMinutes: Int? = null,
    /** Précision affichée sous le filtre. */
    val note: String? = null,
) {
    ENTREE(FilterGroup.TYPE, "Entrée", "🥗", "entrée"),
    PLAT(FilterGroup.TYPE, "Plat", "🍲", "plat"),
    DESSERT(FilterGroup.TYPE, "Dessert", "🍰", "dessert"),
    APERITIF(FilterGroup.TYPE, "Apéro", "🫒", "apéritif"),
    SOUPE(FilterGroup.TYPE, "Soupe", "🥣", "soupe"),
    SALADE(FilterGroup.TYPE, "Salade", "🥬", "salade"),
    ACCOMPAGNEMENT(FilterGroup.TYPE, "Accompagnement", "🥔", "accompagnement"),
    BRUNCH(FilterGroup.TYPE, "Brunch", "🥐", "brunch"),
    GOUTER(FilterGroup.TYPE, "Goûter", "🍪", "goûter"),

    VIANDE(FilterGroup.PRINCIPAL, "Viande", "🥩", "viande", require = Words.viande),
    VOLAILLE(FilterGroup.PRINCIPAL, "Volaille", "🍗", "poulet", require = Words.volaille),
    POISSON(FilterGroup.PRINCIPAL, "Poisson", "🐟", "poisson", require = Words.poisson),
    FRUITS_DE_MER(FilterGroup.PRINCIPAL, "Fruits de mer", "🦐", "fruits de mer", require = Words.fruitsDeMer),
    OEUFS(FilterGroup.PRINCIPAL, "Œufs", "🥚", "oeufs", require = Words.oeufs),
    LEGUMES(FilterGroup.PRINCIPAL, "Légumes", "🥕", "légumes", require = Words.legumes),
    PATES(FilterGroup.PRINCIPAL, "Pâtes", "🍝", "pâtes", require = Words.pates),
    RIZ(FilterGroup.PRINCIPAL, "Riz & céréales", "🍚", "riz", require = Words.riz),
    LEGUMINEUSES(FilterGroup.PRINCIPAL, "Légumineuses", "🫘", "lentilles", require = Words.legumineuses),
    FROMAGE(FilterGroup.PRINCIPAL, "Fromage", "🧀", "fromage", require = Words.fromage),

    VEGETARIEN(FilterGroup.REGIME, "Végétarien", "🌱", "végétarien",
        exclude = Words.viande + Words.volaille + Words.poisson + Words.fruitsDeMer),
    VEGAN(FilterGroup.REGIME, "Vegan", "🌿", "vegan",
        exclude = Words.viande + Words.volaille + Words.poisson + Words.fruitsDeMer + Words.oeufs + Words.laitier + listOf("miel")),
    SANS_GLUTEN(FilterGroup.REGIME, "Sans gluten", "🌾", "sans gluten", exclude = Words.gluten),
    SANS_LACTOSE(FilterGroup.REGIME, "Sans lactose", "🥛", "sans lactose", exclude = Words.laitier),
    HALAL(FilterGroup.REGIME, "Halal", "☪️", "halal", exclude = Words.porcEtAlcool,
        note = "Titres sans porc ni alcool ; vérifie la viande à l'achat."),

    MAX_20(FilterGroup.TEMPS, "≤ 20 min", "⚡", "rapide", maxMinutes = 20),
    MAX_45(FilterGroup.TEMPS, "≤ 45 min", "⏱️", null, maxMinutes = 45),

    LEGER(FilterGroup.ENVIE, "Léger", "🍃", "léger"),
    FAMILIAL(FilterGroup.ENVIE, "Familial", "👨‍👩‍👧", "facile"),
    ECONOMIQUE(FilterGroup.ENVIE, "Économique", "💶", "économique"),
    PLAT_UNIQUE(FilterGroup.ENVIE, "Plat unique", "🍛", "plat complet"),
    FOUR(FilterGroup.ENVIE, "Au four", "🔥", "au four"),
    MIJOTE(FilterGroup.ENVIE, "Mijoté", "🫕", "mijoté"),
    SAISON(FilterGroup.ENVIE, "De saison", "🍂", "de saison"),
}

enum class RecipeSort(val label: String) { PERTINENCE("Pertinence"), NOTE("Mieux notées"), RAPIDE("Plus rapides") }

/** Raccourcis de l'accueil de Découvrir (« Idées rapides »). */
data class QuickIdea(val label: String, val emoji: String, val filters: Set<RecipeFilter>)

object RecipeFilters {

    val quickIdeas = listOf(
        QuickIdea("Dessert en 20 min", "🍰", setOf(RecipeFilter.DESSERT, RecipeFilter.MAX_20)),
        QuickIdea("Plat végétarien", "🌱", setOf(RecipeFilter.PLAT, RecipeFilter.VEGETARIEN)),
        QuickIdea("Poisson au four", "🐟", setOf(RecipeFilter.POISSON, RecipeFilter.FOUR)),
        QuickIdea("Soupe de saison", "🥣", setOf(RecipeFilter.SOUPE, RecipeFilter.SAISON)),
        QuickIdea("Plat familial", "👨‍👩‍👧", setOf(RecipeFilter.PLAT, RecipeFilter.FAMILIAL)),
        QuickIdea("Apéro rapide", "🫒", setOf(RecipeFilter.APERITIF, RecipeFilter.MAX_20)),
    )

    /** Tuiles « Je cherche… » de l'écran Découvrir. */
    val tiles = listOf(RecipeFilter.ENTREE, RecipeFilter.PLAT, RecipeFilter.DESSERT, RecipeFilter.APERITIF, RecipeFilter.SOUPE, RecipeFilter.BRUNCH)

    /** Active ou retire un filtre ; dans un groupe à choix unique, le nouveau remplace l'ancien. */
    fun toggle(current: Set<RecipeFilter>, f: RecipeFilter): Set<RecipeFilter> = when {
        f in current -> current - f
        f.group.single -> current.filterNot { it.group == f.group }.toSet() + f
        else -> current + f
    }

    /** Texte envoyé aux sites : saisie + mots des filtres (dans l'ordre des groupes). Vide = pas de recherche. */
    fun query(text: String, filters: Set<RecipeFilter>): String =
        (listOf(text.trim()) + filters.sortedBy { it.ordinal }.mapNotNull { it.query })
            .filter { it.isNotBlank() }.joinToString(" ")

    private fun titleKey(title: String) = " " + ShoppingListMerger.key(title) + " "

    /**
     * Le titre contient-il l'un des mots, en mot entier (« vin » ne prend pas « vinaigrette ») ? Les deux côtés passent
     * par la même clé normalisée (sans accents, au singulier), donc « crevettes » trouve « crevette ».
     */
    private fun mentions(title: String, words: List<String>): Boolean {
        val t = titleKey(title)
        return words.any { w -> ShoppingListMerger.key(w).takeIf { it.isNotEmpty() }?.let { t.contains(" $it ") } == true }
    }

    /** Garde les recettes compatibles avec les filtres, puis les trie. Durée ou ingrédient inconnus : on garde. */
    fun apply(hits: List<RecipeHit>, filters: Set<RecipeFilter>, sort: RecipeSort = RecipeSort.PERTINENCE): List<RecipeHit> {
        val principal = filters.filter { it.group == FilterGroup.PRINCIPAL }
        val maxMinutes = filters.mapNotNull { it.maxMinutes }.minOrNull()
        val kept = hits.filter { h ->
            filters.none { it.exclude.isNotEmpty() && mentions(h.title, it.exclude) } &&
                (principal.isEmpty() || principal.any { mentions(h.title, it.require) }) &&
                (maxMinutes == null || h.totalMinutes == null || h.totalMinutes <= maxMinutes)
        }
        return when (sort) {
            RecipeSort.PERTINENCE -> kept
            RecipeSort.NOTE -> kept.sortedWith(compareByDescending<RecipeHit> { it.rating ?: -1.0 }.thenByDescending { it.ratingCount ?: 0 })
            RecipeSort.RAPIDE -> kept.sortedBy { it.totalMinutes ?: Int.MAX_VALUE }
        }
    }

    /** Pastille de catégorie d'une recette, d'après son titre (ingrédient principal d'abord, puis type). */
    fun badge(title: String): RecipeFilter? =
        // Les catégories précises avant « Viande » (« poulet rôti » est de la volaille).
        RecipeFilter.entries.filter { it.group == FilterGroup.PRINCIPAL }.sortedBy { if (it == RecipeFilter.VIANDE) 1 else 0 }
            .firstOrNull { mentions(title, it.require) }
            ?: listOf(RecipeFilter.DESSERT to listOf("gateau", "tarte", "mousse", "crumble", "cake", "fondant", "cookie", "clafoutis", "tiramisu", "brownie", "flan", "creme brulee", "panna cotta", "sorbet", "glace"),
                RecipeFilter.SOUPE to listOf("soupe", "veloute", "potage", "bouillon", "gaspacho", "minestrone"),
                RecipeFilter.SALADE to listOf("salade"))
                .firstOrNull { (_, w) -> mentions(title, w) }?.first
}

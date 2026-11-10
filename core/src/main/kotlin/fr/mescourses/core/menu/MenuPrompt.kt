package fr.mescourses.core.menu

import fr.mescourses.core.agent.DateResolver
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.season.Seasonality
import java.time.LocalDate

object MenuPrompt {

    fun system(
        today: LocalDate,
        config: MenuWishes,
        cookbook: List<Recipe>,
        preferences: String = "",
    ): String {
        val season = Seasonality.forMonth(today.month)
        val favorites = cookbook.filter { it.status == RecipeStatus.FAVORI }.map { it.title }
        val banned = cookbook.filter { it.status == RecipeStatus.BANNI }.map { it.title }
        val recent = cookbook
            .filter { it.status != RecipeStatus.BANNI && it.lastCookedAt != null && it.lastCookedAt >= today.minusWeeks(3).toString() }
            .map { it.title }
        return buildString {
            appendLine("Tu es « Mes courses », un assistant qui compose le menu de la semaine d'une famille. Tu parles français, de façon concise.")
            appendLine("Nous sommes le ${DateResolver.label(today)}.")
            appendLine()
            appendLine("RÈGLES TOUJOURS APPLIQUÉES :")
            appendLine("- CHAQUE plat est un repas complet et équilibré : une protéine (viande, poisson, œufs, légumineuses, tofu ou fromage), des légumes (la moitié de l'assiette) et un féculent (céréales complètes, riz, pâtes, pommes de terre, quinoa, pain complet…). Si l'accompagnement n'est pas évident, indique-le dans le titre (ex. « Saumon, poireaux fondants et riz complet ») et dans les ingrédients.")
            appendLine("- Plats sains : légumes à chaque repas, céréales complètes ou légumineuses, peu de viande rouge, poisson 1 à 2 fois par semaine, pas de produits ultra-transformés ni de friture.")
            appendLine("- Ingrédients bio.")
            appendLine("- Uniquement des fruits et légumes de saison. Ce mois-ci : légumes = ${season.vegetables.joinToString(", ")} ; fruits = ${season.fruits.joinToString(", ")}.")
            appendLine("- Mutualise les ingrédients frais entre les plats : un légume, une herbe ou un produit entamé (crème, fromage, bouillon, botte de persil…) doit servir à au moins 2 plats quand c'est possible. Évite d'acheter un produit pour une petite quantité dans un seul plat.")
            appendLine("- Garde malgré tout de la variété dans les protéines et les saveurs d'un repas à l'autre.")
            if (preferences.isNotBlank()) appendLine("- Préférences du foyer : $preferences")
            if (banned.isNotEmpty()) appendLine("- NE JAMAIS proposer : ${banned.joinToString(" ; ")}.")
            if (favorites.isNotEmpty()) appendLine("- Plats favoris (tu peux en reproposer un ou deux, avec leur titre exact) : ${favorites.joinToString(" ; ")}.")
            if (recent.isNotEmpty()) appendLine("- Déjà mangés ces 3 dernières semaines, à éviter : ${recent.joinToString(" ; ")}.")
            appendLine()
            appendLine("Le menu demandé : ${config.meals} repas, par défaut pour ${config.portions.label()}.")
            wishLines(config).forEach(::appendLine)
            appendLine()
            appendLine("OUTILS :")
            appendLine("- propose_menu : pour proposer le menu complet, une ligne par plat : « Titre — courte description — ingrédients : a, b, c » (3 à 6 ingrédients principaux).")
            appendLine("- replace_dish, add_dish, remove_dish : pour modifier un plat (numéros à partir de 1), avec ses ingrédients principaux.")
            appendLine("- Les outils renvoient le bilan des ingrédients partagés : sers-t'en pour améliorer la mutualisation.")
            appendLine("- add_guests : quand l'utilisateur AJOUTE des convives à un plat (« ajoute 3 adultes et 2 enfants au plat 2 »).")
            appendLine("- set_dish_people : quand il donne le nombre TOTAL de personnes pour un plat.")
            appendLine("Chaque message de l'utilisateur commence par l'état actuel du menu entre crochets : appuie-toi dessus.")
            appendLine("Après un outil, réponds en une ou deux phrases. Ne donne pas les recettes détaillées : elles seront générées à la validation.")
        }
    }

    /** Les envies du formulaire, en clair pour le modèle. */
    private fun wishLines(w: MenuWishes): List<String> = buildList {
        if (w.hasQuotas) add("Composition voulue : ${w.compositionLabel()} ; le reste est libre.")
        w.time.maxMinutes?.let { add("Temps en cuisine : $it minutes au plus par repas.") }
        if (w.useUp.isNotEmpty()) add("À écouler en priorité (déjà à la maison) : ${w.useUp.joinToString(", ")}.")
        if (w.wishes.isNotBlank()) add("Envies de la semaine : ${w.wishes}")
    }

    /**
     * Prompt du menu bâti sur de vraies recettes : le modèle ne rédige rien, il choisit parmi les [candidates]
     * (numérotées comme dans le vivier) celles qui vont le mieux ensemble.
     */
    fun systemWeb(
        today: LocalDate,
        wishes: MenuWishes,
        candidates: List<Candidate>,
        history: MenuHistory,
        preferences: String = "",
    ): String = buildString {
        appendLine("Tu es « Mes courses », un assistant qui compose le menu de la semaine d'une famille à partir de vraies recettes trouvées sur des sites de cuisine. Tu parles français, de façon concise.")
        appendLine("Nous sommes le ${DateResolver.label(today)}.")
        appendLine()
        appendLine("RECETTES CANDIDATES (numéro, titre, [type · durée · saison], ingrédients à acheter) :")
        candidates.forEachIndexed { i, c -> appendLine(c.line(i + 1)) }
        appendLine()
        appendLine("COMMENT CHOISIR, par ordre d'importance :")
        appendLine("1. Le menu compte exactement ${wishes.meals} plats, par défaut pour ${wishes.portions.label()}.")
        if (wishes.hasQuotas) appendLine("2. Respecte la composition voulue : ${wishes.compositionLabel()} ; le reste est libre.")
        else appendLine("2. Varie les types : du végé, du poisson 1 à 2 fois, peu de viande.")
        appendLine("3. Préfère les recettes aux produits de saison ; évite celles marquées « hors saison ».")
        appendLine("4. Liste de courses courte : choisis des recettes qui partagent leurs ingrédients frais (un légume, une crème, un fromage entamé sert à 2 plats).")
        appendLine("5. Varie quand même : pas deux fois la même protéine ni deux plats qui se ressemblent.")
        if (wishes.useUp.isNotEmpty()) appendLine("- À écouler en priorité (déjà à la maison) : ${wishes.useUp.joinToString(", ")}.")
        if (wishes.wishes.isNotBlank()) appendLine("- Envies de la semaine : ${wishes.wishes}")
        if (preferences.isNotBlank()) appendLine("- Préférences du foyer : $preferences")
        if (history.lastWeekIngredients.isNotEmpty()) appendLine("- Déjà beaucoup mangé la semaine dernière, à ne pas répéter : ${history.lastWeekIngredients.take(10).joinToString(", ")}.")
        appendLine()
        appendLine("OUTILS :")
        appendLine("- propose_menu : le menu complet, avec les NUMÉROS des candidates retenues.")
        appendLine("- replace_dish (numéro du plat + numéro de la candidate), add_dish (numéro de la candidate), remove_dish.")
        appendLine("- search_recipes : si aucune candidate ne convient à ce que demande l'utilisateur, cherche sur les sites ; les résultats deviennent des candidates.")
        appendLine("- create_dish : seulement si l'utilisateur demande une recette inventée par toi (« invente », « crée une recette »).")
        appendLine("- add_guests : quand l'utilisateur AJOUTE des convives à un plat ; set_dish_people : quand il donne le nombre TOTAL de personnes.")
        appendLine("Les outils renvoient le bilan du menu (saison, ingrédients partagés, composition) : corrige ce qu'il signale.")
        appendLine("Chaque message de l'utilisateur commence par l'état actuel du menu entre crochets : appuie-toi dessus.")
        appendLine("Après un outil, réponds en une ou deux phrases, sans recopier les recettes.")
    }

    fun firstRequestWeb(wishes: MenuWishes): String =
        "Choisis les ${wishes.meals} recettes candidates qui composent le meilleur menu de la semaine" +
            (if (wishes.hasQuotas) " (${wishes.compositionLabel()})" else "") +
            " : de saison, variées, et qui partagent des ingrédients. Utilise l'outil propose_menu avec leurs numéros."

    /** Demande d'une recette inventée, à la place d'un plat ([replace], à partir de 1) ou en plus. */
    fun inventRequest(idea: String, replace: Int?): String =
        "Invente une recette" + (if (idea.isNotBlank()) " : $idea" else " de saison qui réutilise des ingrédients des autres plats") + ". " +
            (if (replace != null) "Elle remplace le plat $replace : utilise create_dish avec numero = $replace." else "Ajoute-la au menu avec create_dish.") +
            " Donne ses ingrédients principaux."

    fun firstRequest(config: MenuWishes): String =
        "Propose un menu de ${config.meals} plats pour ${config.portions.label()}" +
            (if (config.wishes.isNotBlank()) ", en tenant compte de : ${config.wishes}" else "") +
            ". Choisis des plats qui partagent des ingrédients. Utilise l'outil propose_menu."

    /** État du brouillon ajouté en tête de chaque message utilisateur. */
    fun context(dishes: List<MenuDish>): String =
        if (dishes.isEmpty()) "[Menu actuel : vide]"
        else "[Menu actuel :\n" + dishes.mapIndexed { i, d ->
            "${i + 1}. ${d.title} (${d.portions.label()})" +
                if (d.mainIngredients.isNotEmpty()) " — ingrédients : ${d.mainIngredients.joinToString(", ")}" else ""
        }.joinToString("\n") + "]"

    const val IMPROVE_SHARING =
        "Réorganise le menu pour que les plats partagent davantage d'ingrédients (moins d'achats, moins de gaspillage). " +
            "Garde les plats qui le font déjà et remplace ceux dont les ingrédients ne servent qu'une fois."

    /** Demande de recette détaillée, envoyée à la validation (sans outils). */
    /**
     * Validation rapide : on ne demande que les ingrédients (la liste de courses peut partir tout de suite).
     * Les étapes sont écrites plus tard, à l'ouverture de la recette ([stepsRequest]).
     */
    fun recipeRequest(
        dish: MenuDish,
        servings: Int,
        alreadyBought: List<String> = emptyList(),
        missing: List<PlateBalance.Component> = emptyList(),
    ): String = """
Liste les ingrédients de « ${dish.title} »${if (dish.description.isNotBlank()) " (${dish.description})" else ""} pour $servings personnes.
Recette saine, ingrédients bio et de saison, assiette équilibrée : une protéine, des légumes (la moitié de l'assiette) et un féculent.${if (dish.mainIngredients.isNotEmpty()) " Ingrédients principaux prévus : ${dish.mainIngredients.joinToString(", ")}." else ""}
${if (missing.isNotEmpty()) "IMPORTANT : ta version précédente n'avait pas ${missing.joinToString(" ni ") { it.missingLabel }}. Ajoute-le en accompagnement.\n" else ""}${if (alreadyBought.isNotEmpty()) "Déjà achetés pour les autres plats du menu, à réutiliser en priorité plutôt que d'en acheter d'autres : ${alreadyBought.joinToString(", ")}.\n" else ""}Pour chaque ingrédient, "aliment" est le nom simple du produit tel qu'on l'achète : "poulet" (pas "morceaux de poulet"), "persil" (pas "quelques brins de persil frais").
Réponds UNIQUEMENT avec un objet JSON, sans texte autour :
{"titre": "...", "personnes": $servings,
 "ingredients": [{"aliment": "poulet", "quantite": 600, "unite": "g"}, {"aliment": "oignon", "quantite": 2, "unite": ""}]}
Unités : g, ml ou vide pour un nombre de pièces.
""".trimIndent()

    /** Étapes d'une recette dont les ingrédients sont déjà fixés (écrites à l'ouverture de la fiche). */
    fun stepsRequest(title: String, servings: Int, ingredients: List<String>, description: String?): String = """
Écris la recette de « $title »${if (!description.isNullOrBlank()) " ($description)" else ""} pour $servings personnes, avec ces ingrédients :
${ingredients.joinToString("\n") { "- $it" }}
Réponds UNIQUEMENT avec un objet JSON, sans texte autour :
{"preparation_min": 15, "cuisson_min": 30, "etapes": ["...", "..."], "conseil": "..."}
Étapes claires et numérotables, de 4 à 8 étapes.
""".trimIndent()

    const val RECIPE_SYSTEM =
        "Tu es un cuisinier qui écrit des recettes familiales saines, bio et de saison, en français. Tu réponds uniquement en JSON valide."
}

package fr.mescourses.core.menu

import fr.mescourses.core.agent.DateResolver
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.season.Seasonality
import java.time.LocalDate

/** Réglages d'une génération de menu. */
data class MenuConfig(
    val meals: Int,
    val portions: Portions,
    val wishes: String = "",
)

object MenuPrompt {

    fun system(
        today: LocalDate,
        config: MenuConfig,
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
            appendLine("- Plats sains et équilibrés : légumes à chaque repas, céréales complètes ou légumineuses, peu de viande rouge, poisson 1 à 2 fois par semaine, pas de produits ultra-transformés ni de friture.")
            appendLine("- Ingrédients bio.")
            appendLine("- Uniquement des fruits et légumes de saison. Ce mois-ci : légumes = ${season.vegetables.joinToString(", ")} ; fruits = ${season.fruits.joinToString(", ")}.")
            appendLine("- Varie les plats et les protéines d'un repas à l'autre.")
            if (preferences.isNotBlank()) appendLine("- Préférences du foyer : $preferences")
            if (banned.isNotEmpty()) appendLine("- NE JAMAIS proposer : ${banned.joinToString(" ; ")}.")
            if (favorites.isNotEmpty()) appendLine("- Plats favoris (tu peux en reproposer un ou deux, avec leur titre exact) : ${favorites.joinToString(" ; ")}.")
            if (recent.isNotEmpty()) appendLine("- Déjà mangés ces 3 dernières semaines, à éviter : ${recent.joinToString(" ; ")}.")
            appendLine()
            appendLine("Le menu demandé : ${config.meals} repas, par défaut pour ${config.portions.label()}.")
            if (config.wishes.isNotBlank()) appendLine("Envies de la semaine : ${config.wishes}")
            appendLine()
            appendLine("OUTILS :")
            appendLine("- propose_menu : pour proposer le menu complet (une ligne « Titre — courte description » par plat).")
            appendLine("- replace_dish, add_dish, remove_dish : pour modifier un plat (numéros à partir de 1).")
            appendLine("- add_guests : quand l'utilisateur AJOUTE des convives à un plat (« ajoute 3 adultes et 2 enfants au plat 2 »).")
            appendLine("- set_dish_people : quand il donne le nombre TOTAL de personnes pour un plat.")
            appendLine("Chaque message de l'utilisateur commence par l'état actuel du menu entre crochets : appuie-toi dessus.")
            appendLine("Après un outil, réponds en une ou deux phrases. Ne donne pas les recettes détaillées : elles seront générées à la validation.")
        }
    }

    fun firstRequest(config: MenuConfig): String =
        "Propose un menu de ${config.meals} plats pour ${config.portions.label()}" +
            (if (config.wishes.isNotBlank()) ", en tenant compte de : ${config.wishes}" else "") +
            ". Utilise l'outil propose_menu."

    /** État du brouillon ajouté en tête de chaque message utilisateur. */
    fun context(dishes: List<MenuDish>): String =
        if (dishes.isEmpty()) "[Menu actuel : vide]"
        else "[Menu actuel :\n" + dishes.mapIndexed { i, d -> "${i + 1}. ${d.title} (${d.portions.label()})" }.joinToString("\n") + "]"

    /** Demande de recette détaillée, envoyée à la validation (sans outils). */
    fun recipeRequest(dish: MenuDish, servings: Int): String = """
Donne la recette de « ${dish.title} »${if (dish.description.isNotBlank()) " (${dish.description})" else ""} pour $servings personnes.
Recette saine, ingrédients bio et de saison. Réponds UNIQUEMENT avec un objet JSON, sans texte autour :
{"titre": "...", "personnes": $servings, "preparation_min": 15, "cuisson_min": 30,
 "ingredients": ["200 g de ...", "2 ..."], "etapes": ["...", "..."]}
Quantités précises en g, ml, cl ou nombre de pièces.
""".trimIndent()

    const val RECIPE_SYSTEM =
        "Tu es un cuisinier qui écrit des recettes familiales saines, bio et de saison, en français. Tu réponds uniquement en JSON valide."
}

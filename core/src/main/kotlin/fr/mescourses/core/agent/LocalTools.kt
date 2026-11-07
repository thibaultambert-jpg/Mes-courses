package fr.mescourses.core.agent

import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.MealSlot
import fr.mescourses.core.model.PlannedMeal
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.recipes.RecipeImporter
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.recipes.WebSearch
import fr.mescourses.core.repository.MealPlanRepository
import fr.mescourses.core.repository.RecipeRepository
import fr.mescourses.core.repository.ShoppingRepository
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/** Construit les outils « locaux » (livre de recettes, planning, liste de courses, recherche web). */
class LocalTools(
    private val recipes: RecipeRepository,
    private val shopping: ShoppingRepository,
    private val plan: MealPlanRepository,
    private val webSearch: WebSearch?,
    private val importer: RecipeImporter?,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    fun all(): List<AgentTool> = listOfNotNull(
        searchCookbook,
        saveRecipe,
        webSearch?.let { searchRecipesWeb },
        importer?.let { getRecipeFromUrl },
        planMeal,
        getMealPlan,
        addRecipeToShoppingList,
        addToShoppingList,
        getShoppingList,
    )

    private fun tool(
        name: String,
        description: String,
        parameters: JsonObject,
        run: suspend (JsonObject) -> JsonElement,
    ): AgentTool = object : AgentTool {
        override val name = name
        override val description = description
        override val parameters = parameters
        override suspend fun execute(args: JsonObject): JsonElement =
            try { run(args) } catch (e: Exception) { toolError(e.message ?: e.toString()) }
    }

    private fun Recipe.summary() = buildJsonObject {
        put("id", id); put("titre", title); put("personnes", servings)
        if (tags.isNotEmpty()) put("tags", tags.joinToString(", "))
    }

    val searchCookbook = tool(
        "search_cookbook",
        "Cherche dans le livre de recettes de l'utilisateur (recettes déjà enregistrées). Requête vide = toutes.",
        params { string("query", "Mots-clés, ex. « poulet », « végétarien ». Peut être vide.", required = false) },
    ) { args ->
        val found = recipes.search(args.str("query").orEmpty()).take(15)
        toolOk("${found.size} recette(s) trouvée(s)", buildJsonArray { found.forEach { add(it.summary()) } })
    }

    val saveRecipe = tool(
        "save_recipe",
        "Enregistre une recette dans le livre de recettes. Soit `url` (import d'une page web), soit titre + ingrédients + étapes.",
        params {
            string("url", "Adresse d'une page de recette à importer", required = false)
            string("title", "Titre de la recette", required = false)
            integer("servings", "Nombre de personnes", required = false)
            stringArray("ingredients", "Ingrédients avec quantités, ex. « 200 g de farine »", required = false)
            stringArray("steps", "Étapes de préparation", required = false)
        },
    ) { args ->
        val url = args.str("url")
        val recipe = when {
            url != null -> requireNotNull(importer) { "Import web indisponible" }.import(url)
            else -> Recipe(
                title = args.str("title") ?: return@tool toolError("Il faut un titre ou une url."),
                servings = args.int("servings") ?: 4,
                ingredients = IngredientParser.parseAll(args.strList("ingredients")),
                steps = args.strList("steps"),
                tags = listOf("généré"),
            )
        }
        val id = recipes.save(recipe)
        toolOk("Recette « ${recipe.title} » enregistrée", buildJsonObject { put("id", id) })
    }

    val searchRecipesWeb = tool(
        "search_recipes_web",
        "Cherche des recettes sur internet (Marmiton, 750g, Cuisine AZ…). Renvoie titres et adresses.",
        params { string("query", "Plat ou ingrédients recherchés, ex. « gratin dauphinois »") },
    ) { args ->
        val query = args.str("query") ?: return@tool toolError("query manquant")
        val results = RecipeSites.rank(requireNotNull(webSearch).search(RecipeSites.recipeQuery(query), 8)).take(5)
        toolOk("${results.size} résultat(s)", buildJsonArray {
            results.forEach { add(buildJsonObject { put("titre", it.title); put("url", it.url) }) }
        })
    }

    val getRecipeFromUrl = tool(
        "get_recipe_from_url",
        "Lit une recette sur une page web (sans l'enregistrer) : ingrédients et étapes.",
        params { string("url", "Adresse de la page") },
    ) { args ->
        val r = requireNotNull(importer).import(args.str("url") ?: return@tool toolError("url manquante"))
        toolOk(r.title, buildJsonObject {
            put("titre", r.title); put("personnes", r.servings)
            putJsonArray("ingredients") { r.ingredients.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.raw)) } }
            putJsonArray("etapes") { r.steps.take(8).forEach { add(kotlinx.serialization.json.JsonPrimitive(it.take(200))) } }
        })
    }

    val planMeal = tool(
        "plan_meal",
        "Planifie une recette du livre de recettes pour un repas. La recette doit être enregistrée (utilise son id).",
        params {
            integer("recipe_id", "Id de la recette (voir search_cookbook / save_recipe)")
            string("date", "Date AAAA-MM-JJ, ou « demain », « lundi »…")
            string("meal", "Repas", required = false, enum = listOf("dejeuner", "diner", "petit-dejeuner"))
            integer("servings", "Nombre de personnes", required = false)
        },
    ) { args ->
        val id = args.long("recipe_id") ?: return@tool toolError("recipe_id manquant")
        val recipe = recipes.get(id) ?: return@tool toolError("Recette $id introuvable")
        val date = DateResolver.resolve(args.str("date"), today())
        val meal = PlannedMeal(
            date = date.toString(),
            slot = MealSlot.parse(args.str("meal")),
            recipeId = id,
            recipeTitle = recipe.title,
            servings = args.int("servings") ?: recipe.servings,
        )
        plan.add(meal)
        toolOk("« ${recipe.title} » planifié ${DateResolver.label(date)} (${meal.slot.label}, ${meal.servings} pers.)")
    }

    val getMealPlan = tool(
        "get_meal_plan",
        "Liste les repas planifiés (par défaut les 14 prochains jours).",
        params {
            string("from", "Date de début AAAA-MM-JJ", required = false)
            string("to", "Date de fin AAAA-MM-JJ", required = false)
        },
    ) { args ->
        val from = DateResolver.resolve(args.str("from"), today())
        val to = args.str("to")?.let { DateResolver.resolve(it, today()) } ?: from.plusDays(14)
        val meals = plan.between(from.toString(), to.toString())
        toolOk("${meals.size} repas planifié(s)", buildJsonArray {
            meals.forEach {
                add(buildJsonObject {
                    put("date", it.date); put("repas", it.slot.label); put("recette", it.recipeTitle)
                    put("recipe_id", it.recipeId); put("personnes", it.servings)
                })
            }
        })
    }

    val addRecipeToShoppingList = tool(
        "add_recipe_to_shopping_list",
        "Ajoute les ingrédients d'une recette enregistrée à la liste de courses, ajustés au nombre de personnes.",
        params {
            integer("recipe_id", "Id de la recette")
            integer("servings", "Nombre de personnes", required = false)
        },
    ) { args ->
        val recipe = recipes.get(args.long("recipe_id") ?: return@tool toolError("recipe_id manquant"))
            ?: return@tool toolError("Recette introuvable")
        val items = ShoppingListMerger.fromRecipe(recipe, args.int("servings") ?: recipe.servings)
        shopping.add(items)
        toolOk("${items.size} ingrédient(s) de « ${recipe.title} » ajouté(s) à la liste")
    }

    val addToShoppingList = tool(
        "add_to_shopping_list",
        "Ajoute des articles libres à la liste de courses (ex. « lessive », « 6 yaourts nature »).",
        params { stringArray("items", "Articles à ajouter") },
    ) { args ->
        val items = args.strList("items").map(ShoppingListMerger::fromText)
        if (items.isEmpty()) return@tool toolError("Aucun article")
        shopping.add(items)
        toolOk("${items.size} article(s) ajouté(s) : ${items.joinToString { it.name }}")
    }

    val getShoppingList = tool(
        "get_shopping_list",
        "Affiche la liste de courses actuelle, fusionnée (doublons regroupés).",
        params { },
    ) { _ ->
        val merged = ShoppingListMerger.merge(shopping.all())
        toolOk("${merged.size} article(s)", buildJsonArray {
            merged.forEach { add(kotlinx.serialization.json.JsonPrimitive(ShoppingListMerger.format(it))) }
        })
    }
}

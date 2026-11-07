package fr.mescourses.core.menu

import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.agent.int
import fr.mescourses.core.agent.params
import fr.mescourses.core.agent.str
import fr.mescourses.core.agent.strList
import fr.mescourses.core.agent.toolError
import fr.mescourses.core.agent.toolOk
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.repository.MenuDraftRepository
import fr.mescourses.core.repository.RecipeRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Outils donnés au LLM pour construire le menu. Volontairement peu nombreux et simples
 * (modèle de 2 à 4 milliards de paramètres sur téléphone).
 */
class MenuTools(
    private val draft: MenuDraftRepository,
    private val recipes: RecipeRepository,
    private val defaultPortions: () -> Portions,
) {
    fun all(): List<AgentTool> = listOf(proposeMenu, replaceDish, addDish, removeDish, addGuests, setDishPeople)

    private fun tool(name: String, description: String, parameters: JsonObject, run: suspend (JsonObject) -> JsonElement) =
        object : AgentTool {
            override val name = name
            override val description = description
            override val parameters = parameters
            override suspend fun execute(args: JsonObject): JsonElement =
                try { run(args) } catch (e: Exception) { toolError(e.message ?: e.toString()) }
        }

    private suspend fun index() = CookbookIndex(recipes.all())

    /** Crée un plat en le rattachant au livre s'il y est déjà ; null s'il est dans « Ne plus proposer ». */
    private fun dish(
        index: CookbookIndex,
        title: String,
        description: String,
        portions: Portions,
        ingredients: List<String> = emptyList(),
    ): MenuDish? {
        val existing = index.find(title)
        if (existing?.banned == true) return null
        return MenuDish(
            title = existing?.title ?: title,
            description = description.ifBlank { existing?.description.orEmpty() },
            portions = portions,
            recipeId = existing?.id,
            // Pour un plat du livre, ses vrais ingrédients font foi.
            mainIngredients = existing?.ingredients?.map { it.name }?.takeIf { it.isNotEmpty() }?.take(8) ?: ingredients,
        )
    }

    /** Message de l'outil + bilan de mutualisation, pour que le modèle puisse s'améliorer. */
    private suspend fun withOverlap(message: String): JsonElement {
        val dishes = draft.get()
        val parts = listOfNotNull(
            message,
            IngredientOverlap.summary(IngredientOverlap.compute(dishes)).ifEmpty { null },
            PlateBalance.menuSummary(dishes),
        )
        return toolOk(parts.joinToString(". "))
    }

    private fun bannedError(title: String) =
        toolError("« $title » est dans la liste « Ne plus proposer » de l'utilisateur : propose un autre plat.")

    private suspend fun numbered(args: JsonObject): Pair<Int, List<MenuDish>> {
        val dishes = draft.get()
        val n = args.int("numero") ?: args.int("number") ?: error("numero manquant")
        require(n in 1..dishes.size) { "Le plat $n n'existe pas (le menu a ${dishes.size} plats)." }
        return n - 1 to dishes
    }

    val proposeMenu = tool(
        "propose_menu",
        "Remplace le menu par une nouvelle proposition complète.",
        params {
            stringArray(
                "plats",
                "Un élément par plat : « Titre — courte description — ingrédients : a, b, c » (3 à 6 ingrédients principaux)",
            )
        },
    ) { args ->
        // Un élément par plat ; on ne découpe pas sur les virgules (elles séparent les ingrédients).
        val lines = (args["plats"] ?: args["dishes"]).let { v ->
            when (v) {
                is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                is kotlinx.serialization.json.JsonPrimitive -> v.content.lines()
                else -> emptyList()
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return@tool toolError("Aucun plat")
        val index = index()
        val refused = mutableListOf<String>()
        val dishes = lines.mapNotNull { line ->
            val parsed = MenuTextParser.parseLine(line)
            dish(index, parsed.title, parsed.description, defaultPortions(), parsed.ingredients) ?: run { refused += parsed.title; null }
        }
        draft.set(dishes)
        val msg = "Menu de ${dishes.size} plats enregistré" +
            if (refused.isNotEmpty()) ". Refusés car « Ne plus proposer » : ${refused.joinToString()} — remplace-les avec add_dish" else ""
        withOverlap(msg)
    }

    val replaceDish = tool(
        "replace_dish",
        "Remplace un plat du menu par un autre (garde ses convives).",
        params {
            integer("numero", "Numéro du plat à remplacer (à partir de 1)")
            string("titre", "Titre du nouveau plat")
            string("description", "Courte description", required = false)
            stringArray("ingredients", "Ingrédients principaux (réutilise ceux des autres plats si possible)", required = false)
        },
    ) { args ->
        val (i, dishes) = numbered(args)
        val title = args.str("titre") ?: return@tool toolError("titre manquant")
        val new = dish(index(), title, args.str("description").orEmpty(), dishes[i].portions, args.strList("ingredients"))
            ?: return@tool bannedError(title)
        draft.set(dishes.toMutableList().also { it[i] = new })
        withOverlap("Plat ${i + 1} remplacé par « ${new.title} »")
    }

    val addDish = tool(
        "add_dish",
        "Ajoute un plat au menu.",
        params {
            string("titre", "Titre du plat")
            string("description", "Courte description", required = false)
            stringArray("ingredients", "Ingrédients principaux (réutilise ceux des autres plats si possible)", required = false)
        },
    ) { args ->
        val title = args.str("titre") ?: return@tool toolError("titre manquant")
        val new = dish(index(), title, args.str("description").orEmpty(), defaultPortions(), args.strList("ingredients"))
            ?: return@tool bannedError(title)
        val dishes = draft.get() + new
        draft.set(dishes)
        withOverlap("« ${new.title} » ajouté (plat ${dishes.size})")
    }

    val removeDish = tool(
        "remove_dish",
        "Retire un plat du menu.",
        params { integer("numero", "Numéro du plat (à partir de 1)") },
    ) { args ->
        val (i, dishes) = numbered(args)
        draft.set(dishes.toMutableList().also { it.removeAt(i) })
        toolOk("« ${dishes[i].title} » retiré")
    }

    val addGuests = tool(
        "add_guests",
        "Ajoute des convives à un plat (ex. des invités) : les nombres s'ajoutent aux convives actuels.",
        params {
            integer("numero", "Numéro du plat (à partir de 1)")
            integer("adultes", "Adultes à ajouter", required = false)
            integer("enfants", "Enfants à ajouter", required = false)
        },
    ) { args ->
        val (i, dishes) = numbered(args)
        val p = dishes[i].portions + Portions(args.int("adultes") ?: 0, args.int("enfants") ?: 0)
        if (p.adults < 0 || p.children < 0) return@tool toolError("Nombre de convives négatif")
        draft.set(dishes.toMutableList().also { it[i] = it[i].copy(portions = p) })
        toolOk("Plat ${i + 1} « ${dishes[i].title} » : ${p.label()}")
    }

    val setDishPeople = tool(
        "set_dish_people",
        "Fixe le nombre TOTAL de convives d'un plat.",
        params {
            integer("numero", "Numéro du plat (à partir de 1)")
            integer("adultes", "Nombre total d'adultes")
            integer("enfants", "Nombre total d'enfants", required = false)
        },
    ) { args ->
        val (i, dishes) = numbered(args)
        val p = Portions(args.int("adultes") ?: dishes[i].portions.adults, args.int("enfants") ?: 0)
        if (p.adults < 0 || p.children < 0) return@tool toolError("Nombre de convives négatif")
        draft.set(dishes.toMutableList().also { it[i] = it[i].copy(portions = p) })
        toolOk("Plat ${i + 1} « ${dishes[i].title} » : ${p.label()}")
    }
}

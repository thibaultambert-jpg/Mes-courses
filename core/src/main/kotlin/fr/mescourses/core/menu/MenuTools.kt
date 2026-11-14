package fr.mescourses.core.menu

import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.agent.int
import fr.mescourses.core.agent.intList
import fr.mescourses.core.agent.params
import fr.mescourses.core.agent.str
import fr.mescourses.core.agent.strList
import fr.mescourses.core.agent.toolError
import fr.mescourses.core.agent.toolOk
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.stripAccents
import fr.mescourses.core.repository.MenuDraftRepository
import fr.mescourses.core.repository.RecipeRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Outils donnés au LLM pour construire le menu. Volontairement peu nombreux et simples
 * (modèle de 2 à 4 milliards de paramètres sur téléphone).
 */
class MenuTools(
    private val draft: MenuDraftRepository,
    private val recipes: RecipeRepository,
    private val defaultPortions: () -> Portions,
    /** Recettes trouvées sur les sites, que le modèle désigne par leur numéro. Vide = le chef invente les plats. */
    private val pool: CandidatePool = CandidatePool(),
    private val wishes: () -> MenuWishes? = { null },
    private val today: () -> LocalDate = { LocalDate.now() },
    /** Recherche sur les sites à la demande du modèle (null = indisponible). */
    private val search: (suspend (String) -> List<Candidate>)? = null,
    /** Retouches permises pendant cette demande (null = sans limite) : le modèle a tendance à en faire plusieurs. */
    private val maxChanges: Int? = null,
) {
    private var changes = 0

    /** « type » demandé au modèle : entrée ou dessert ; tout le reste = plat principal (null). */
    private fun courseOf(args: JsonObject): fr.mescourses.core.catalog.Course? =
        when (args.str("type")?.lowercase()?.stripAccents()?.trim()) {
            "entree", "entrees", "starter" -> fr.mescourses.core.catalog.Course.ENTREE
            "dessert", "desserts" -> fr.mescourses.core.catalog.Course.DESSERT
            else -> null
        }

    /** Avec des candidates : le menu se compose par numéros ; sans (hors ligne) : le chef invente, comme avant. */
    /**
     * [adjusting] : le menu existe déjà et l'utilisateur demande une retouche. « propose_menu » est alors retiré : un
     * petit modèle s'en sert pour tout, et réécrit le menu entier (plats perdus) au lieu de toucher un seul plat.
     */
    fun all(adjusting: Boolean = false): List<AgentTool> =
        if (pool.size > 0) listOfNotNull(pickMenu.takeUnless { adjusting }, swapDish, pickDish, removeDish, searchRecipes.takeIf { search != null }, createDish, addGuests, setDishPeople)
        else listOf(proposeMenu, replaceDish, addDish, removeDish, createDish, addGuests, setDishPeople)

    private fun tool(name: String, description: String, parameters: JsonObject, changing: Boolean = true, run: suspend (JsonObject) -> JsonElement) =
        object : AgentTool {
            override val name = name
            override val description = description
            override val parameters = parameters
            override suspend fun execute(args: JsonObject): JsonElement {
                if (changing && maxChanges != null && changes >= maxChanges) {
                    return toolError("La retouche demandée est déjà faite : ne modifie rien d'autre. Réponds maintenant à l'utilisateur en une phrase.")
                }
                val result = try { run(args) } catch (e: Exception) { toolError(e.message ?: e.toString()) }
                if (changing && (result as? JsonObject)?.get("ok")?.toString() == "true") changes++
                return result
            }
        }

    /** La recette est-elle déjà au menu (même adresse ou même titre) ? [except] : position du plat qu'elle remplace. */
    private fun alreadyThere(c: Candidate, dishes: List<MenuDish>, except: Int? = null): Boolean {
        val url = c.recipe.sourceUrl?.let(fr.mescourses.core.recipes.RecipeSites::normalize)
        val title = fr.mescourses.core.shopping.ShoppingListMerger.key(c.recipe.title)
        return dishes.withIndex().any { (i, d) ->
            i != except && ((url != null && d.sourceUrl?.let(fr.mescourses.core.recipes.RecipeSites::normalize) == url) ||
                fr.mescourses.core.shopping.ShoppingListMerger.key(d.title) == title)
        }
    }

    private fun duplicate(c: Candidate) = toolError("« ${c.recipe.title} » est déjà au menu : choisis une autre candidate.")

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
        val mains = dishes.filter { it.isMain }
        val parts = listOfNotNull(
            message,
            IngredientOverlap.summary(IngredientOverlap.compute(dishes)).ifEmpty { null },
            PlateBalance.menuSummary(mains),
            // Menu bâti sur de vraies recettes : on peut vérifier la saison et la composition demandée.
            if (pool.size > 0) MenuInsights.of(mains, today().month).summary(mains.size, wishes()).ifEmpty { null } else null,
        )
        return toolOk(parts.joinToString(" ") { if (it.endsWith(".")) it else "$it." })
    }

    private fun bannedError(title: String) =
        toolError("« $title » est dans la liste « Ne plus proposer » de l'utilisateur : propose un autre plat.")

    private suspend fun numbered(args: JsonObject): Pair<Int, List<MenuDish>> {
        val dishes = draft.get()
        val n = args.int("numero") ?: args.int("number") ?: error("numero manquant")
        require(n in 1..dishes.size) { "Le plat $n n'existe pas (le menu a ${dishes.size} plats)." }
        return n - 1 to dishes
    }

    /** Candidate désignée par son numéro ; à défaut par son titre (le modèle écrit parfois le nom). */
    private fun candidate(args: JsonObject): Candidate? =
        (args.int("candidat") ?: args.int("candidate"))?.let(pool::get)
            ?: args.str("titre")?.let { t -> pool.all().firstOrNull { CookbookIndex(listOf(it.recipe)).find(t) != null } }

    private fun noCandidate() = toolError(
        "Candidate inconnue : donne le numéro d'une recette de la liste (1 à ${pool.size}), cherche-en avec search_recipes, " +
            "ou utilise create_dish si l'utilisateur veut une recette inventée.",
    )

    private suspend fun proposeNumbers(numbers: List<Int>): JsonElement {
        val known = numbers.distinct().mapNotNull(pool::get)
        if (known.isEmpty()) return noCandidate()
        // Pas deux fois le même titre, et pas plus de deux plats de même forme (pâtes, tarte, gratin…).
        val picked = ArrayList<Candidate>()
        val refused = ArrayList<String>()
        for (c in known) {
            val sameForm = c.form != null && picked.count { it.form == c.form } >= DishForms.MAX
            val sameTitle = picked.any { fr.mescourses.core.shopping.ShoppingListMerger.key(it.recipe.title) == fr.mescourses.core.shopping.ShoppingListMerger.key(c.recipe.title) }
            if (sameForm || sameTitle) refused += c.recipe.title else picked += c
        }
        // Les entrées et desserts déjà choisis restent au menu.
        draft.set(picked.map { it.toDish(defaultPortions()) } + draft.get().filter { !it.isMain })
        val unknown = numbers.distinct().filter { pool.get(it) == null }
        return withOverlap(
            "Menu de ${picked.size} plats enregistré" +
                (if (unknown.isNotEmpty()) " (numéros inconnus ignorés : ${unknown.joinToString()})" else "") +
                if (refused.isNotEmpty()) " (écartés, trop de plats du même genre : ${refused.joinToString()})" else "",
        )
    }

    val pickMenu = tool(
        "propose_menu",
        "Remplace le menu par les recettes candidates choisies.",
        // Une chaîne plutôt qu'un tableau : le modèle du téléphone écrit souvent des tableaux mal formés ([1,2,]).
        params { string("numeros", "Numéros des recettes candidates retenues, séparés par des virgules. Exemple : 3, 7, 12") },
    ) { args -> proposeNumbers(args.intList("numeros").ifEmpty { args.intList("plats") }) }

    val swapDish = tool(
        "replace_dish",
        "Remplace un plat du menu par une autre recette candidate (garde ses convives).",
        params {
            integer("numero", "Numéro du plat à remplacer dans le menu (à partir de 1)")
            integer("candidat", "Numéro de la recette candidate qui le remplace")
        },
    ) { args ->
        val (i, dishes) = numbered(args)
        val c = candidate(args) ?: return@tool noCandidate()
        if (alreadyThere(c, dishes, except = i)) return@tool duplicate(c)
        draft.set(dishes.toMutableList().also { it[i] = c.toDish(dishes[i].portions, dishes[i].course) })
        withOverlap("Plat ${i + 1} remplacé par « ${c.recipe.title} »")
    }

    val pickDish = tool(
        "add_dish",
        "Ajoute une recette candidate au menu, comme plat, entrée ou dessert (paramètre « type »).",
        params {
            integer("candidat", "Numéro de la recette candidate à ajouter")
            string("type", "entree, plat ou dessert (plat par défaut)", required = false)
        },
    ) { args ->
        val c = candidate(args) ?: return@tool noCandidate()
        if (alreadyThere(c, draft.get())) return@tool duplicate(c)
        val dishes = draft.get() + c.toDish(defaultPortions(), courseOf(args))
        draft.set(dishes)
        withOverlap("« ${c.recipe.title} » ajouté (plat ${dishes.size})")
    }

    val searchRecipes = tool(
        "search_recipes",
        "Cherche d'autres recettes sur les sites (ex. « curry de légumes ») et les ajoute aux candidates.",
        params { string("mots", "Mots à chercher : un plat ou un ingrédient") },
        changing = false,
    ) { args ->
        val words = args.str("mots") ?: args.str("query") ?: return@tool toolError("mots manquants")
        val found = search?.invoke(words).orEmpty()
        if (found.isEmpty()) return@tool toolError("Aucune recette trouvée pour « $words ». Essaie d'autres mots, ou create_dish si l'utilisateur veut une recette inventée.")
        val numbers = pool.add(found)
        toolOk("Nouvelles candidates :\n" + found.zip(numbers).joinToString("\n") { (c, n) -> c.line(n) })
    }

    val createDish = tool(
        "create_dish",
        "Crée une recette inventée par toi, UNIQUEMENT quand l'utilisateur le demande. Avec « numero », elle remplace ce plat ; sinon elle s'ajoute au menu.",
        params {
            string("titre", "Titre du plat")
            string("description", "Courte description", required = false)
            stringArray("ingredients", "Ingrédients principaux, de saison (réutilise ceux des autres plats si possible)", required = false)
            integer("numero", "Numéro du plat à remplacer (à partir de 1)", required = false)
            string("type", "entree, plat ou dessert (plat par défaut)", required = false)
        },
    ) { args ->
        val title = args.str("titre") ?: return@tool toolError("titre manquant")
        val dishes = draft.get()
        val at = args.int("numero")?.takeIf { it in 1..dishes.size }?.minus(1)
        val new = dish(index(), title, args.str("description").orEmpty(), at?.let { dishes[it].portions } ?: defaultPortions(), args.strList("ingredients"))
            ?.let { if (it.recipeId == null) it.copy(invented = true) else it }
            ?: return@tool bannedError(title)
        draft.set(if (at != null) dishes.toMutableList().also { it[at] = new.copy(course = dishes[at].course) } else dishes + new.copy(course = courseOf(args)))
        withOverlap(if (at != null) "Plat ${at + 1} remplacé par la recette inventée « ${new.title} »" else "Recette inventée « ${new.title} » ajoutée (plat ${dishes.size + 1})")
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
        draft.set(dishes.toMutableList().also { it[i] = new.copy(course = dishes[i].course) })
        withOverlap("Plat ${i + 1} remplacé par « ${new.title} »")
    }

    val addDish = tool(
        "add_dish",
        "Ajoute un plat, une entrée ou un dessert au menu (paramètre « type »).",
        params {
            string("titre", "Titre du plat")
            string("description", "Courte description", required = false)
            stringArray("ingredients", "Ingrédients principaux (réutilise ceux des autres plats si possible)", required = false)
            string("type", "entree, plat ou dessert (plat par défaut)", required = false)
        },
    ) { args ->
        val title = args.str("titre") ?: return@tool toolError("titre manquant")
        val new = dish(index(), title, args.str("description").orEmpty(), defaultPortions(), args.strList("ingredients"))
            ?: return@tool bannedError(title)
        val dishes = draft.get() + new.copy(course = courseOf(args))
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

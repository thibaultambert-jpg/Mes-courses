package fr.mescourses.core

import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.menu.Candidate
import fr.mescourses.core.menu.CandidateFinder
import fr.mescourses.core.menu.CandidatePool
import fr.mescourses.core.menu.Chosen
import fr.mescourses.core.menu.CookingTime
import fr.mescourses.core.menu.DishKind
import fr.mescourses.core.menu.DishKinds
import fr.mescourses.core.menu.MenuComposer
import fr.mescourses.core.menu.MenuHistory
import fr.mescourses.core.menu.MenuInsights
import fr.mescourses.core.menu.MenuPrompt
import fr.mescourses.core.menu.MenuTools
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.MenuWishes
import fr.mescourses.core.menu.Novelty
import fr.mescourses.core.menu.ShoppingPreview
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.MenuHistoryEntry
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.recipes.PageFetcher
import fr.mescourses.core.recipes.RecipeImporter
import fr.mescourses.core.recipes.RecipeSearcher
import fr.mescourses.core.recipes.RecipeSites
import fr.mescourses.core.season.Seasonality
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Refonte du menu : recherche sur les sites, saison, historique, composition et liste de courses. */
class MenuPlannerTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val recipes = InMemoryRecipes()
    private val history = InMemoryHistory()
    private val draft = InMemoryDraft()
    private val wishes = MenuWishes(meals = 3, portions = Portions(2, 0))
    private val noHistory = MenuHistory(emptyList(), emptyList(), today)

    private fun recipe(title: String, vararg ingredients: String, url: String? = null, minutes: Int? = 30, id: Long = 0, status: RecipeStatus = RecipeStatus.HISTORIQUE) =
        Recipe(id = id, title = title, ingredients = IngredientParser.parseAll(ingredients.toList()), sourceUrl = url, cookMinutes = minutes, status = status)

    private fun candidate(title: String, vararg ingredients: String, w: MenuWishes = wishes, past: MenuHistory = noHistory, fromCookbook: Boolean = false) =
        Candidate.of(recipe(title, *ingredients, url = "https://www.marmiton.org/recettes/recette_${title.hashCode()}.aspx"), w, past, today, emptyList(), fromCookbook)

    // ---------- Saison ----------

    @Test fun saison_d_un_ingredient() {
        assertEquals(true, Seasonality.inSeason("2 poireaux", Month.NOVEMBER))
        assertEquals(false, Seasonality.inSeason("tomates cerises", Month.NOVEMBER))
        assertEquals(true, Seasonality.inSeason("tomates cerises", Month.JULY))
        // Le nom le plus long l'emporte : une pomme de terre n'est pas une pomme.
        assertEquals(true, Seasonality.inSeason("pommes de terre", Month.JUNE))
        assertEquals(false, Seasonality.inSeason("pomme", Month.JUNE))
        // Conserves et produits transformés : la saison ne compte pas.
        assertNull(Seasonality.inSeason("concentré de tomate", Month.NOVEMBER))
        assertNull(Seasonality.inSeason("tomates pelées en boîte", Month.NOVEMBER))
        assertNull(Seasonality.inSeason("blanc de poulet", Month.NOVEMBER))
        // Les variétés d'une courge sont reconnues.
        assertEquals(true, Seasonality.inSeason("potimarron", Month.NOVEMBER))
        assertEquals(false, Seasonality.inSeason("butternut", Month.JUNE))
    }

    @Test fun bilan_de_saison_d_une_recette() {
        val r = Seasonality.report(listOf("Tian de courgettes", "2 courgettes", "3 tomates", "1 oignon", "200 g de poireaux"), Month.NOVEMBER)
        assertEquals(listOf("poireau"), r.seasonal) // l'oignon se trouve toute l'année : ni l'un ni l'autre
        assertEquals(listOf("courgette", "tomate"), r.outOfSeason)
    }

    // ---------- Type de plat ----------

    @Test fun type_de_plat() {
        assertEquals(DishKind.VIANDE, DishKinds.of("Quiche aux poireaux", listOf("poireaux", "lardons fumés", "œufs")))
        assertEquals(DishKind.POISSON, DishKinds.of("Papillote de cabillaud", listOf("cabillaud", "poireaux")))
        assertEquals(DishKind.VEGE, DishKinds.of("Curry de pois chiches", listOf("pois chiches", "bouillon de volaille", "épinards")))
        assertEquals(DishKind.VEGE, DishKinds.of("Œufs à la coque", listOf("œufs", "pain")))
    }

    // ---------- Formulaire ----------

    @Test fun composition_ramenee_au_nombre_de_repas() {
        val w = MenuWishes(4, Portions(2), vegetarian = 3, fish = 2, meat = 2).normalized()
        assertEquals(listOf(3, 1, 0), listOf(w.vegetarian, w.fish, w.meat))
        assertEquals("3 végé, 1 poisson", w.compositionLabel())
        assertEquals(30, CookingTime.EXPRESS.maxMinutes)
    }

    // ---------- Recherches ----------

    private fun finder(fetcher: PageFetcher) = CandidateFinder(
        RecipeSearcher(fetcher), RecipeImporter(listOf(fetcher)), recipes, history,
        pantry = { emptyList() }, today = { today }, sites = listOf(RecipeSites.byId("marmiton")!!),
    )

    @Test fun recherches_de_saison_selon_la_composition_et_les_envies() {
        val f = finder { "" }
        val w = MenuWishes(5, Portions(2), time = CookingTime.EXPRESS, vegetarian = 2, fish = 1, useUp = listOf("chou rouge"), wishes = "une raclette samedi, Petit budget")
        val q = f.queries(w, noHistory, today)
        assertEquals(listOf("chou rouge rapide", "raclette", "économique"), q.take(3))
        val seasonal = q.drop(3)
        assertEquals(6, seasonal.size)
        assertTrue(seasonal.all { it.endsWith(" rapide") }, q.toString())
        assertTrue(seasonal.count { "végétarien" in it } >= 2 && seasonal.any { "poisson" in it }, q.toString())
        // Uniquement des légumes de saison en novembre.
        val november = Seasonality.seasonalVegetables(Month.NOVEMBER)
        assertTrue(seasonal.all { s -> november.any { s.startsWith(it) } }, q.toString())
        assertTrue(seasonal.none { "tomate" in it || "courgette" in it })
    }

    @Test fun les_recherches_changent_d_une_semaine_a_l_autre() {
        val f = finder { "" }
        val thisWeek = f.queries(wishes, noHistory, today)
        val nextWeek = f.queries(wishes, noHistory, today.plusWeeks(1))
        assertTrue(thisWeek != nextWeek)
        // Le légume déjà au menu la semaine dernière passe en fin de liste.
        val first = thisWeek.first()
        val past = MenuHistory(listOf(MenuHistoryEntry(date = "2026-11-04", title = "Gratin", mainIngredients = listOf(fr.mescourses.core.menu.IngredientOverlap.key(first)))), emptyList(), today)
        assertTrue(first !in f.queries(wishes, past, today), "$first devrait attendre")
    }

    private fun searchPage(vararg entries: Pair<String, String>) = "<html><body>" + entries.joinToString("") { (slug, title) ->
        """<div class="card"><a href="/recettes/recette_$slug.aspx"><h4>$title</h4></a><div>4,6/5 · 25 min</div></div>"""
    } + "</body></html>"

    private fun recipePage(title: String, vararg ingredients: String, minutes: Int = 25) = """<html><head><script type="application/ld+json">
        {"@context":"https://schema.org","@type":"Recipe","name":"$title","recipeYield":"4","totalTime":"PT${minutes}M","cookTime":"PT${minutes}M",
         "recipeIngredient":[${ingredients.joinToString(",") { "\"$it\"" }}],"recipeInstructions":["Cuire."]}</script></head></html>"""

    private val site = mapOf(
        "gratin-poireaux_1" to recipePage("Gratin de poireaux au jambon", "4 poireaux", "4 tranches de jambon", "50 cl de lait", "100 g de comté", "500 g de pommes de terre"),
        "tarte-tomate_2" to recipePage("Tarte à la tomate", "4 tomates", "1 pâte brisée", "2 c. à soupe de moutarde"),
        "lasagnes_3" to recipePage("Lasagnes à la bolognaise", "500 g de boeuf haché", "12 feuilles de lasagne", "1 oignon"),
        "pot-au-feu_4" to recipePage("Pot-au-feu", "1 kg de boeuf", "4 poireaux", "4 carottes", "4 pommes de terre", minutes = 180),
        "curry-chou-fleur_5" to recipePage("Curry de chou-fleur aux pois chiches", "1 chou-fleur", "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz"),
        "mousse-chocolat_6" to recipePage("Mousse au chocolat", "200 g de chocolat", "6 œufs", "1 pincée de sel"),
        "soupe-courge_7" to recipePage("Soupe de courge", "1 kg de courge", "1 oignon"),
    )
    private val titles = mapOf(
        "gratin-poireaux_1" to "Gratin de poireaux au jambon", "tarte-tomate_2" to "Tarte à la tomate", "lasagnes_3" to "Lasagnes à la bolognaise",
        "pot-au-feu_4" to "Pot-au-feu", "curry-chou-fleur_5" to "Curry de chou-fleur aux pois chiches", "mousse-chocolat_6" to "Mousse au chocolat",
        "soupe-courge_7" to "Soupe de courge",
    )
    private val fetched = mutableListOf<String>()
    private val web = PageFetcher { url ->
        fetched += url
        if ("recherche.aspx" in url) searchPage(*titles.entries.map { it.key to it.value }.toTypedArray())
        else site.entries.firstOrNull { it.key in url }?.value ?: error("404")
    }

    @Test fun candidates_lues_sur_les_sites_et_triees() = runTest {
        recipes.save(Recipe(title = "Lasagnes à la bolognaise", status = RecipeStatus.BANNI))
        val progress = mutableListOf<String>()
        val found = finder(web).find(wishes.copy(time = CookingTime.NORMAL, novelty = Novelty.DECOUVERTES)) { progress += it }
        val names = found.candidates.map { it.recipe.title }
        // Bannie, trop longue (3 h), dessert et recette de 2 ingrédients : écartées.
        assertEquals(setOf("Gratin de poireaux au jambon", "Curry de chou-fleur aux pois chiches", "Tarte à la tomate"), names.toSet())
        // La tarte à la tomate, hors saison en novembre, passe en dernier.
        assertEquals("Tarte à la tomate", names.last())
        val gratin = found.candidates.first { "Gratin" in it.recipe.title }
        assertEquals(DishKind.VIANDE, gratin.kind)
        assertEquals(listOf("poireau"), gratin.seasonal)
        assertEquals(25, gratin.minutes)
        assertEquals("Marmiton", gratin.siteName)
        assertTrue("poireaux" in gratin.mainIngredients && "lait" in gratin.mainIngredients, gratin.mainIngredients.toString())
        assertTrue(gratin.line(1).startsWith("1. Gratin de poireaux au jambon [viande · 25 min · saison : poireau]"), gratin.line(1))
        assertTrue(found.failedSites.isEmpty())
        assertTrue(progress.any { "Recherche sur 1 sites" in it } && progress.any { it.startsWith("Lecture des recettes") })
        // Chaque fiche n'est lue qu'une fois, même si elle sort de plusieurs recherches.
        assertEquals(1, fetched.count { "gratin-poireaux_1" in it })
    }

    @Test fun un_plat_du_menu_precedent_n_est_pas_repropose() = runTest {
        history.add(listOf(MenuHistoryEntry(date = "2026-11-02", title = "Gratin de poireaux au jambon",
            sourceUrl = "https://marmiton.org/recettes/recette_gratin-poireaux_1.aspx", mainIngredients = listOf("poireau", "jambon"))))
        val found = finder(web).find(wishes.copy(novelty = Novelty.DECOUVERTES))
        assertFalse(found.candidates.any { "Gratin" in it.recipe.title })
        // Au bout de quatre semaines, il revient.
        val later = CandidateFinder(RecipeSearcher(web), RecipeImporter(listOf(web)), recipes, history, { emptyList() }, { today.plusWeeks(5) }, listOf(RecipeSites.byId("marmiton")!!))
        assertTrue(later.find(wishes.copy(novelty = Novelty.DECOUVERTES)).candidates.any { "Gratin" in it.recipe.title })
    }

    @Test fun menu_compose_depuis_le_catalogue_sans_reseau() = runTest {
        fun entry(n: Int, title: String, vararg ingredients: String): fr.mescourses.core.catalog.CatalogEntry {
            val url = "https://www.marmiton.org/recettes/recette_cat-$n.aspx"
            val html = """<script type="application/ld+json">{"@type":"Recipe","name":"$title","recipeCategory":"Plat principal","totalTime":"PT30M",
                "recipeIngredient":[${ingredients.joinToString(",") { "\"$it\"" }}]}</script>"""
            return fr.mescourses.core.catalog.CatalogEntry.of(fr.mescourses.core.recipes.RecipeJsonLdExtractor.extractDetails(html, url)!!, "marmiton", url)
        }
        val vegetables = Seasonality.seasonalVegetables(Month.NOVEMBER)
        val catalog = InMemoryCatalog(vegetables.mapIndexed { i, v -> entry(i, "Poêlée de $v au poulet", "500 g de $v", "2 blancs de poulet", "200 g de riz") } +
            entry(90, "Raclette", "800 g de fromage à raclette", "1 kg de pommes de terre", "8 tranches de jambon"))
        val offline = CandidateFinder(RecipeSearcher { error("pas de réseau") }, RecipeImporter(listOf(PageFetcher { error("pas de réseau") })), recipes, history,
            { emptyList() }, { today }, listOf(RecipeSites.byId("marmiton")!!), catalog)
        val found = offline.find(wishes.copy(novelty = Novelty.DECOUVERTES))
        assertTrue(found.candidates.size >= 3, "candidates : ${found.candidates.map { it.recipe.title }}")
        assertTrue(found.candidates.all { it.recipe.sourceUrl!!.startsWith("https://www.marmiton.org") && it.minutes == 30 })
        assertTrue(found.failedSites.isEmpty())
        // L'outil de recherche du chef passe aussi par le catalogue.
        assertEquals(listOf("Raclette"), offline.search("une raclette", wishes).map { it.recipe.title })
    }

    @Test fun sites_injoignables() = runTest {
        val found = finder { error("pas de réseau") }.find(wishes.copy(novelty = Novelty.DECOUVERTES))
        assertTrue(found.candidates.isEmpty())
        assertEquals(listOf("Marmiton"), found.failedSites)
    }

    @Test fun favoris_du_livre_selon_la_part_de_nouveautes() = runTest {
        recipes.save(recipe("Dahl de lentilles", "300 g de lentilles corail", "1 oignon", "20 cl de lait de coco", "200 g de riz", status = RecipeStatus.FAVORI))
        recipes.save(recipe("Hachis parmentier", "500 g de boeuf", "1 kg de pommes de terre", "1 oignon").copy(lastCookedAt = "2026-11-03"))
        val offline = finder { error("pas de réseau") }
        assertTrue(offline.find(wishes.copy(novelty = Novelty.DECOUVERTES)).candidates.isEmpty())
        val classics = offline.find(wishes.copy(novelty = Novelty.CLASSIQUES)).candidates
        // Le hachis de la semaine dernière attendra.
        assertEquals(listOf("Dahl de lentilles"), classics.map { it.recipe.title })
        assertTrue(classics.single().fromCookbook)
    }

    // ---------- Composition ----------

    private val leekTart = candidate("Tarte aux poireaux", "3 poireaux", "20 cl de crème fraîche", "3 œufs", "1 pâte brisée", "100 g de comté")
    private val leekSoup = candidate("Velouté de poireaux et pommes de terre", "4 poireaux", "500 g de pommes de terre", "10 cl de crème fraîche", "100 g de comté")
    private val salmon = candidate("Saumon aux endives braisées", "4 pavés de saumon", "6 endives", "200 g de riz", "10 cl de crème fraîche")
    private val chicken = candidate("Poulet rôti aux panais", "1 poulet", "4 panais", "500 g de pommes de terre")
    private val beef = candidate("Boeuf aux carottes", "800 g de boeuf", "1 kg de carottes", "200 g de riz")
    private val summer = candidate("Ratatouille au quinoa", "2 courgettes", "2 aubergines", "4 tomates", "200 g de quinoa", "100 g de feta")
    private val all get() = listOf(leekTart, leekSoup, salmon, chicken, beef, summer)

    @Test fun note_propre_saison_equilibre_et_restes() {
        assertTrue(summer.base < leekTart.base, "hors saison : ${summer.base} / ${leekTart.base}")
        assertEquals(listOf("courgette", "aubergine", "tomate"), summer.outOfSeason)
        // Les restes à écouler remontent la recette qui les utilise.
        val withEndives = candidate("Saumon aux endives braisées", "4 pavés de saumon", "6 endives", "200 g de riz", w = wishes.copy(useUp = listOf("Endives")))
        val without = candidate("Saumon aux endives braisées", "4 pavés de saumon", "6 endives", "200 g de riz")
        assertEquals(3.0, withEndives.base - without.base)
        // Ce qu'on a mangé la semaine dernière pèse contre une recette.
        val past = MenuHistory(listOf(MenuHistoryEntry(date = "2026-11-05", title = "Quiche", mainIngredients = listOf("poireau", "creme"))), emptyList(), today)
        assertTrue(candidate("Tarte aux poireaux", "3 poireaux", "20 cl de crème fraîche", "3 œufs", "1 pâte brisée", past = past).base < leekTart.base)
    }

    @Test fun le_compositeur_respecte_la_composition_demandee() {
        val w = wishes.copy(meals = 3, vegetarian = 1, fish = 1, meat = 1, novelty = Novelty.DECOUVERTES)
        val menu = MenuComposer.compose(all, w)
        assertEquals(3, menu.size)
        assertEquals(setOf(DishKind.VEGE, DishKind.POISSON, DishKind.VIANDE), menu.map { it.kind }.toSet())
        assertFalse(summer in menu, "la ratatouille de novembre ne passe pas")
        // Tout végétarien : la viande et le poisson restent dehors, même mieux notés.
        val veggie = MenuComposer.compose(all, wishes.copy(meals = 2, vegetarian = 2, novelty = Novelty.DECOUVERTES))
        assertTrue(veggie.all { it.kind == DishKind.VEGE }, veggie.map { it.recipe.title }.toString())
    }

    @Test fun le_compositeur_mutualise_sans_proposer_deux_fois_le_meme_plat() {
        val twin = candidate("Tarte fine aux poireaux", "3 poireaux", "20 cl de crème fraîche", "3 œufs", "1 pâte feuilletée", "100 g de comté")
        val menu = MenuComposer.compose(listOf(leekTart, twin, beef), wishes.copy(meals = 2, novelty = Novelty.DECOUVERTES))
        // Deux tartes aux poireaux partagent tout, mais ce n'est pas un menu : la deuxième place va à un autre plat.
        assertEquals(1, menu.count { "arte" in it.recipe.title }, menu.map { it.recipe.title }.toString())
        // À note propre égale, la recette qui réutilise la crème déjà prévue l'emporte.
        val chosen = listOf(Chosen.of(leekTart))
        assertTrue(MenuComposer.score(salmon, chosen, wishes) - salmon.base > MenuComposer.score(beef, chosen, wishes) - beef.base)
    }

    @Test fun mieux_mutualiser_sans_modele() {
        // Tarte et velouté partagent poireaux, crème et comté ; le boeuf aux carottes ne partage rien.
        val menu = listOf(leekTart, beef)
        val swap = MenuComposer.improveSharing(all, wishes.copy(novelty = Novelty.DECOUVERTES), menu.map { Chosen.of(it) to it })
        assertNotNull(swap)
        assertEquals(1, swap.index)
        assertTrue(swap.sharedAfter > swap.sharedBefore)
        assertFalse(swap.candidate == summer, "pas de plat hors saison pour gagner un ingrédient")
        // Rien à gagner : pas d'échange.
        assertNull(MenuComposer.improveSharing(listOf(leekTart, beef), wishes, menu.map { Chosen.of(it) to it }))
    }

    @Test fun bilans_et_prompt_bornes_pour_le_contexte_du_telephone() {
        val many = (1..18).map { i -> candidate("Recette numéro $i aux poireaux", "3 poireaux", "20 cl de crème", "200 g de riz", "100 g de comté", "2 carottes", "1 oignon", "ingrédient$i") }
        val prompt = MenuPrompt.systemWeb(today, wishes.copy(meals = 5), many, noHistory)
        assertTrue(prompt.length < 4500, "prompt de ${prompt.length} caractères")
        assertTrue("n'écris pas ta réflexion" in prompt)
        val dishes = many.take(5).map { it.toDish(Portions(2)) }
        val summary = fr.mescourses.core.menu.IngredientOverlap.summary(fr.mescourses.core.menu.IngredientOverlap.compute(dishes))
        assertTrue(summary.length < 600, summary)
        assertTrue(MenuPrompt.context(dishes).length < 700)
    }

    @Test fun autre_recette_pour_une_place() {
        val others = listOf(Chosen.of(leekTart), Chosen.of(salmon))
        val next = MenuComposer.replacement(all, wishes.copy(novelty = Novelty.DECOUVERTES), others, exclude = setOf(leekTart, salmon, chicken))
        assertNotNull(next)
        assertTrue(next !in setOf(leekTart, salmon, chicken, summer))
    }

    @Test fun part_de_classiques() {
        val favourite = candidate("Hachis parmentier", "500 g de boeuf", "1 kg de pommes de terre", "2 carottes", fromCookbook = true)
        val discoveries = MenuComposer.compose(all + favourite, wishes.copy(meals = 3, novelty = Novelty.DECOUVERTES))
        assertFalse(favourite in discoveries)
        val classics = MenuComposer.compose(all + favourite, wishes.copy(meals = 3, novelty = Novelty.CLASSIQUES))
        assertTrue(favourite in classics)
    }

    // ---------- Bilan du menu et liste de courses ----------

    @Test fun bilan_du_menu_pour_le_modele() {
        val dishes = listOf(leekTart, summer).map { it.toDish(Portions(2)) }
        val insights = MenuInsights.of(dishes, Month.NOVEMBER)
        assertEquals(listOf("poireau"), insights.seasonal)
        assertEquals(listOf(2 to "courgette", 2 to "aubergine", 2 to "tomate"), insights.outOfSeason)
        assertEquals(mapOf(DishKind.VEGE to 2), insights.kinds)
        val text = insights.summary(dishes.size, wishes.copy(meals = 3, fish = 1))
        assertTrue("Hors saison : courgette (plat 2)" in text, text)
        assertTrue("Le menu doit compter 3 plats (il en a 2)" in text, text)
        assertTrue("il manque 1 poisson" in text, text)
    }

    @Test fun apercu_de_la_liste_et_restes() {
        val gratin = recipe("Gratin", "1/2 chou-fleur", "50 cl de lait", "100 g de comté").copy(servings = 4)
        val soup = recipe("Soupe", "50 cl de lait", "3 carottes").copy(servings = 4)
        val preview = ShoppingPreview.of(listOf(gratin to 4.0, soup to 4.0), pantry = emptyList())
        assertEquals(4, preview.items) // chou-fleur, lait (fusionné), comté, carottes
        assertEquals(listOf("½ chou-fleur"), preview.leftovers)
        // Deux recettes qui se partagent le chou-fleur : plus de reste.
        val both = ShoppingPreview.of(listOf(gratin to 4.0, recipe("Curry", "1/2 chou-fleur", "200 g de riz").copy(servings = 4) to 4.0), emptyList())
        assertTrue(both.leftovers.isEmpty())
    }

    // ---------- Outils du modèle ----------

    private val pool = CandidatePool().apply { set(all) }
    private val searched = mutableListOf<String>()
    private val tools = MenuTools(
        draft, recipes, { Portions(2, 1) }, pool, wishes = { wishes.copy(meals = 3, fish = 1) }, today = { today },
        search = { q -> searched += q; listOf(candidate("Curry de légumes d'hiver", "1 chou-fleur", "2 carottes", "20 cl de lait de coco", "200 g de riz")) },
    ).all().associateBy { it.name }

    private suspend fun run(name: String, json: String) = tools.getValue(name).execute(Json.parseToJsonElement(json) as JsonObject).jsonObject
    private fun JsonObject.text() = (this["message"] ?: this["erreur"])!!.jsonPrimitive.content

    @Test fun le_modele_choisit_par_numeros() = runTest {
        assertEquals(setOf("propose_menu", "replace_dish", "add_dish", "remove_dish", "search_recipes", "create_dish", "add_guests", "set_dish_people"), tools.keys)
        // Tolérance : nombres en texte, numéro inconnu.
        val r = run("propose_menu", """{"numeros":[1,"3",99]}""")
        assertTrue("Menu de 2 plats enregistré" in r.text() && "99" in r.text(), r.text())
        assertTrue("Le menu doit compter 3 plats" in r.text(), r.text())
        val d = draft.state.value
        assertEquals(listOf("Tarte aux poireaux", "Saumon aux endives braisées"), d.map { it.title })
        assertEquals(leekTart.recipe.sourceUrl, d[0].sourceUrl)
        assertEquals(30, d[0].totalMinutes)
        assertFalse(d[0].invented)
        assertEquals(Portions(2, 1), d[0].portions)
        assertTrue("Ingrédients partagés : crème (plats 1, 2)" in r.text(), r.text())

        run("add_guests", """{"numero":2,"adultes":2}""")
        val swapped = run("replace_dish", """{"numero":2,"candidat":4}""")
        assertTrue("Poulet rôti aux panais" in swapped.text())
        assertEquals(Portions(4, 1), draft.state.value[1].portions) // les convives restent
        assertTrue("il manque 1 poisson" in swapped.text(), swapped.text())
        assertEquals("false", run("replace_dish", """{"numero":1,"candidat":42}""")["ok"]!!.jsonPrimitive.content)
        run("add_dish", """{"candidat":"5"}""")
        assertEquals(3, draft.state.value.size)
    }

    @Test fun recherche_et_recette_inventee_a_la_demande() = runTest {
        run("propose_menu", """{"numeros":"1, 3"}""")
        val found = run("search_recipes", """{"mots":"curry de légumes"}""")
        assertEquals(listOf("curry de légumes"), searched)
        assertTrue("7. Curry de légumes d'hiver" in found.text(), found.text())
        run("add_dish", """{"candidat":7}""")
        assertEquals("Curry de légumes d'hiver", draft.state.value.last().title)

        val created = run("create_dish", """{"titre":"Poêlée de panais au tofu fumé","ingredients":["panais","tofu fumé","riz"],"numero":1}""")
        assertTrue("inventée" in created.text())
        val first = draft.state.value.first()
        assertTrue(first.invented && first.sourceUrl == null && first.recipeId == null)
        assertEquals(listOf("panais", "tofu fumé", "riz"), first.mainIngredients)
    }

    @Test fun prompt_des_candidates() {
        val past = MenuHistory(listOf(MenuHistoryEntry(date = "2026-11-05", title = "Quiche", mainIngredients = listOf("epinard"))), emptyList(), today)
        val p = MenuPrompt.systemWeb(today, wishes.copy(meals = 3, vegetarian = 2, useUp = listOf("endives"), wishes = "réconfortant"), all, past, "sans arachide")
        assertTrue("1. Tarte aux poireaux [végé · 30 min · saison : poireau]" in p, p)
        assertTrue("hors saison : courgette, aubergine, tomate" in p)
        assertTrue("exactement 3 plats" in p && "2 végé" in p && "endives" in p && "réconfortant" in p && "sans arachide" in p)
        assertTrue("à ne pas répéter : epinard" in p)
        assertTrue("create_dish" in p && "search_recipes" in p && "NUMÉROS" in p)
        assertTrue("propose_menu" in MenuPrompt.firstRequestWeb(wishes))
        assertTrue("numero = 2" in MenuPrompt.inventRequest("un curry", 2))
    }

    // ---------- Validation ----------

    @Test fun validation_d_un_menu_de_recettes_des_sites_sans_generation() = runTest {
        var llmCalls = 0
        val shopping = InMemoryShopping()
        val plan = InMemoryPlan()
        val validator = MenuValidator(
            TextGenerator { _, _ -> llmCalls++; """{"titre":"Poêlée","personnes":2,"ingredients":[{"aliment":"panais","quantite":400,"unite":"g"},{"aliment":"tofu","quantite":200,"unite":"g"},{"aliment":"riz","quantite":150,"unite":"g"}]}""" },
            recipes, shopping, plan, draft, childFactor = { 0.5 }, today = { today }, pantry = { emptyList() },
            history = history, webRecipe = { url -> pool.byUrl(url)?.recipe },
        )
        draft.set(listOf(leekTart.toDish(Portions(2)), leekSoup.toDish(Portions(2)), MenuDish("Poêlée de panais au tofu", "", Portions(2), invented = true)))

        val result = validator.validate()

        assertEquals(1, llmCalls) // seule la recette inventée passe par le modèle
        assertEquals(3, result.newRecipes)
        assertTrue(result.failures.isEmpty())
        val tart = recipes.state.value.first { it.title == "Tarte aux poireaux" }
        assertEquals(leekTart.recipe.sourceUrl, tart.sourceUrl)
        assertEquals("2026-11-09", tart.lastCookedAt)
        assertTrue("menu" in tart.tags)
        // Les poireaux des deux recettes (pour 4, ramenées à 2 personnes) s'additionnent dans la liste.
        val merged = fr.mescourses.core.shopping.ShoppingListMerger.merge(shopping.state.value)
        assertEquals(4.0, merged.first { it.name == "Poireaux" }.quantity)
        // Historique durable : c'est lui qui fera varier la semaine suivante.
        val past = history.state.value
        assertEquals(3, past.size)
        assertEquals(DishKind.VEGE.name, past[0].kind)
        assertTrue("poireau" in past[0].mainIngredients, past[0].mainIngredients.toString())
        val next = MenuHistory(past, recipes.state.value, today.plusWeeks(1))
        assertTrue(next.eatenRecently("Tarte aux poireaux", null))
        assertTrue(next.eatenRecently("Autre titre", leekSoup.recipe.sourceUrl))
        assertTrue(next.repetition(listOf("poireau", "navet")) > 0)
        assertFalse(MenuHistory(past, emptyList(), today.plusWeeks(6)).eatenRecently("Tarte aux poireaux", null))
    }
}

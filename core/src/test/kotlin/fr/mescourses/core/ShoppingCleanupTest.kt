package fr.mescourses.core

import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.RecipeResponseParser
import fr.mescourses.core.menu.RecipeStepsWriter
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.core.model.Ingredient
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.Pantry
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingCleanupTest {

    @Test fun produits_de_placard() {
        listOf(
            "sel", "Sel et poivre du moulin", "poivre noir", "huile d'olive bio", "Vinaigre de cidre", "mayonnaise",
            "ketchup", "moutarde à l'ancienne", "sauce soja", "beurre doux", "lait demi-écrémé", "fleur de sel", "eau",
        ).forEach { assertTrue(Pantry.isStaple(it), it) }
        listOf("lait de coco", "sauce tomate", "beurre de cacahuète", "poivron rouge", "persil", "selle d'agneau", "pâte feuilletée pur beurre")
            .forEach { assertFalse(Pantry.isStaple(it), it) }
    }

    @Test fun aliment_seul() {
        assertEquals("poulet", FoodName.clean("morceaux de poulet"))
        assertEquals("persil", FoodName.clean("Quelques brins de persil frais bio"))
        assertEquals("ail", FoodName.clean(IngredientParser.parse("2 gousses d'ail").name))
        assertEquals("citron", FoodName.clean("jus de citron"))
        assertEquals("oignons", FoodName.clean("oignons émincés"))
        assertEquals("carottes", FoodName.clean("carottes (environ 500 g), pelées"))
        assertEquals("riz complet", FoodName.clean("riz complet bio"))
        assertEquals("pommes de terre", FoodName.clean("pommes de terre"))
    }

    @Test fun recette_vers_liste_sans_placard() {
        val r = Recipe(
            title = "Poulet",
            servings = 2,
            ingredients = IngredientParser.parseAll(
                listOf("400 g de morceaux de poulet", "1 c. à soupe d'huile d'olive", "sel, poivre", "quelques brins de persil frais", "20 g de beurre", "2 œufs"),
            ),
        )
        val items = ShoppingListMerger.fromRecipe(r, 2.0)
        assertEquals(listOf("poulet", "persil", "œufs"), items.map { it.name })
    }

    @Test fun placard_elargi_et_personnalise() {
        listOf("farine de blé", "sucre en poudre", "cassonade", "cumin moulu", "paprika fumé", "herbes de Provence",
            "feuilles de laurier", "levure chimique", "maïzena", "bouillon de volaille", "concentré de tomate", "miel", "noix de muscade")
            .forEach { assertTrue(Pantry.isStaple(it), it) }
        listOf("sucrine", "lait de soja", "piment frais", "poivron vert")
            .forEach { assertFalse(Pantry.isStaple(it), it) }
        // Liste personnalisée : le miel s'achète, le riz est toujours au placard.
        val perso = Pantry.DEFAULTS - "Miel" + "Riz"
        assertFalse(Pantry.isStaple("miel", perso))
        assertTrue(Pantry.isStaple("riz basmati", perso))
        assertFalse(Pantry.isStaple("riz basmati"))
    }

    @Test fun ingredient_de_base() {
        assertEquals("fromage de chèvre", FoodName.clean("fromage de chèvre émietté"))
        assertEquals("framboises", FoodName.clean("framboises pour la décoration"))
        assertEquals("beurre", FoodName.clean("beurre ou margarine"))
        assertEquals("avocats", FoodName.clean("avocats bien mûrs"))
        assertEquals("oignon", FoodName.clean("oignon moyen"))
        assertEquals("carottes", FoodName.clean("carottes râpées"))
        assertEquals("cerises", FoodName.clean("cerises dénoyautées"))
        // Produits vendus tels quels : on garde le nom.
        assertEquals("fromage râpé", FoodName.clean("fromage râpé"))
        assertEquals("amandes effilées", FoodName.clean("amandes effilées"))
        assertEquals("jambon cuit", FoodName.clean("tranches de jambon cuit"))
    }

    @Test fun recherche_au_drive_simplifiee() {
        assertEquals("fromage de chèvre", fr.mescourses.core.cart.CartPlanner.searchQuery(ShoppingItem(name = "fromage de chèvre émietté")))
    }

    @Test fun oeufs_par_douze() {
        fun eggs(vararg q: Double) = ShoppingListMerger.merge(q.map { ShoppingItem(name = "œufs", quantity = it) } + ShoppingItem(name = "jaune d'oeuf", quantity = 1.0))
            .single()
        assertEquals(12.0, eggs(2.0).quantity)
        assertEquals("Œufs", eggs(2.0).name)
        assertEquals(24.0, eggs(10.0, 4.0).quantity)
        assertEquals("oeufs x12", CartPlanner.searchQuery(ShoppingItem(name = "Œufs", quantity = 12.0)))
    }

    @Test fun ingredients_en_objets() {
        val r = RecipeResponseParser.parse(
            """{"titre":"X","personnes":4,"ingredients":[{"aliment":"poulet","quantite":600,"unite":"g"},{"aliment":"oignon","quantite":2,"unite":""},{"aliment":"persil"}]}""",
            "X", 4,
        )
        assertNotNull(r)
        assertEquals(listOf("poulet", "oignon", "persil"), r.ingredients.map { it.name })
        assertEquals(600.0, r.ingredients[0].quantity)
        assertEquals("g", r.ingredients[0].unit)
        assertEquals(2.0, r.ingredients[1].quantity)
        assertNull(r.ingredients[1].unit)
        assertTrue(r.steps.isEmpty())
    }

    @Test fun etapes_a_la_demande() = runTest {
        val recipe = Recipe(
            title = "Velouté", servings = 4, tags = listOf("menu", MenuValidator.STEPS_PENDING),
            ingredients = IngredientParser.parseAll(listOf("1 kg de potimarron")),
        )
        var prompt = ""
        val writer = RecipeStepsWriter(TextGenerator { _, p -> prompt = p; """{"preparation_min":10,"cuisson_min":25,"etapes":["Couper","Cuire","Mixer"],"conseil":"Servir chaud"}""" })
        assertTrue(writer.needsSteps(recipe))
        val done = writer.complete(recipe)
        assertNotNull(done)
        assertTrue("1 kg de potimarron" in prompt)
        assertEquals(3, done.steps.size)
        assertEquals(25, done.cookMinutes)
        assertFalse(writer.needsSteps(done))
        assertEquals("Servir chaud", done.notes)
    }

    @Test fun unites_sans_points_et_mots_de_comptage() {
        IngredientParser.parse("1 c à c thym").let { assertEquals("c. à café", it.unit); assertEquals("thym", it.name) }
        IngredientParser.parse("1 C à s huile d'olive").let { assertEquals("c. à soupe", it.unit); assertEquals("huile d'olive", it.name) }
        IngredientParser.parse("1 unité courge butternut").let {
            assertEquals(1.0, it.quantity); assertNull(it.unit); assertEquals("courge butternut", it.name)
        }
        assertEquals("oignon", FoodName.clean(IngredientParser.parse("1 unité oignon hâché").name))
        // Articles déjà dans la liste : cherchés sous le bon nom.
        assertEquals("oignon", FoodName.clean("Unité oignon hâché"))
        assertEquals("thym", FoodName.clean("C à c thym"))
        // Pas de régression.
        IngredientParser.parse("2 gousses d'ail").let { assertEquals("gousse", it.unit); assertEquals("ail", it.name) }
    }

    @Test fun recette_enregistree_reparee() {
        val r = Recipe(
            title = "Butternut farcie", servings = 2,
            ingredients = listOf(
                Ingredient(name = "C à c thym", quantity = 1.0),
                Ingredient(name = "C à s huile d'olive", quantity = 1.0),
                Ingredient(name = "Unité courge butternut", quantity = 1.0),
                Ingredient(name = "Unité oignon hâché", quantity = 1.0),
                Ingredient(name = "ail", quantity = 2.0, unit = "gousse"),
                Ingredient(name = "persil", quantity = 1.0, unit = "pincée"),
                Ingredient(name = "fromage de chèvre émietté", quantity = 150.0, unit = "g"),
            ),
        )
        val items = ShoppingListMerger.fromRecipe(r, 2.0)
        assertEquals(listOf("courge butternut", "oignon", "ail", "persil", "fromage de chèvre"), items.map { it.name })
        assertEquals(1.0, items[0].quantity); assertNull(items[0].unit)
        assertEquals(ShoppingListMerger.PIECE, items[2].unit); assertEquals(1.0, items[2].quantity)
        assertEquals(ShoppingListMerger.PIECE, items[3].unit)
        assertEquals("g", items[4].unit)
    }

    @Test fun pieces_non_additionnees() {
        val a = Recipe(title = "A", servings = 2, ingredients = listOf(
            Ingredient(name = "ail", quantity = 2.0, unit = "gousse"), Ingredient(name = "oignon", quantity = 3.0, unit = "unités"),
        ))
        val b = Recipe(title = "B", servings = 2, ingredients = listOf(
            Ingredient(name = "ail", quantity = 4.0, unit = "gousse"), Ingredient(name = "oignon", quantity = 1.0),
        ))
        val merged = ShoppingListMerger.merge(ShoppingListMerger.fromRecipe(a, 2.0) + ShoppingListMerger.fromRecipe(b, 2.0))
        val ail = merged.first { it.name == "Ail" }
        assertEquals(1.0, ail.quantity); assertEquals(ShoppingListMerger.PIECE, ail.unit)
    }

    private class FakeSearch(val results: List<fr.mescourses.core.carrefour.Product>) : fr.mescourses.core.cart.RetailerBackend {
        val queries = mutableListOf<String>()
        override val retailer = fr.mescourses.core.retail.Retailer.LECLERC
        override suspend fun search(query: String, limit: Int) = results.filter { it.available }.take(limit)
        override suspend fun searchWithUnavailable(query: String, limit: Int): List<fr.mescourses.core.carrefour.Product> { queries += query; return results.take(limit) }
        override suspend fun cart() = error("")
        override suspend fun addToCart(product: fr.mescourses.core.carrefour.Product, quantity: Int) = error("")
        override suspend fun setQuantity(ean: String, quantity: Int) = error("")
        override suspend fun timeslots(max: Int) = emptyList<fr.mescourses.core.carrefour.TimeSlot>()
        override suspend fun sessionProbe() = null
    }

    private fun p(id: String, name: String) = fr.mescourses.core.carrefour.Product(id, name)

    @Test fun produits_sans_rapport_jamais_choisis() = runTest {
        val b = FakeSearch(listOf(p("1", "Lait corps hydratant Nivea"), p("2", "Thé Earl Grey Twinings"), p("3", "Mouchoirs blancs")))
        val line = CartPlanner.searchLine(b, ShoppingItem(name = "Lardons", quantity = 200.0, unit = "g"), preferOrganic = true)
        assertEquals(fr.mescourses.core.cart.LineStatus.INTROUVABLE, line.status)
        assertFalse(line.included)
        assertEquals(0, line.relevantCount)
        assertEquals(listOf("Lardons"), b.queries) // une seule recherche, sans « bio »
    }

    @Test fun bio_choisi_parmi_les_resultats() = runTest {
        val b = FakeSearch(listOf(p("1", "Mouchoirs"), p("2", "Lardons fumés 200g"), p("3", "Lardons fumés bio 150g")))
        val line = CartPlanner.searchLine(b, ShoppingItem(name = "Lardons"), preferOrganic = true)
        assertEquals("3", line.product?.id)
        assertEquals(2, line.relevantCount)
        assertEquals("1", line.candidates.last().id)
        val classic = CartPlanner.searchLine(FakeSearch(listOf(p("2", "Lardons fumés 200g"))), ShoppingItem(name = "Lardons"), preferOrganic = true)
        assertEquals("2", classic.product?.id)
        assertEquals("Pas de version bio au drive", classic.note)
    }

    @Test fun frais_d_abord() {
        val persil = CartPlanner.rank(ShoppingItem(name = "persil"), listOf(p("1", "Persil séché 15g"), p("2", "Persil surgelé"), p("3", "Persil frais botte")))
        assertEquals("3", persil.first().id)
        val oignon = CartPlanner.rank(ShoppingItem(name = "oignon"), listOf(p("1", "Oignons émincés surgelés"), p("2", "Oignons jaunes filet 1kg")))
        assertEquals("2", oignon.first().id)
        // Hors fruits et légumes : pas d'effet.
        assertEquals(0, CartPlanner.freshness(ShoppingItem(name = "riz"), p("1", "Riz frais")))
    }

    @Test fun elargir_et_recherche_corrigee() {
        assertEquals("coulis de tomate", CartPlanner.widen("coulis de tomate jaune"))
        assertEquals("coulis", CartPlanner.widen("coulis de tomate"))
        assertNull(CartPlanner.widen("lardons"))
        val item = ShoppingItem(name = "Coulis de tomate jaune")
        assertEquals("coulis de tomate", CartPlanner.queryFor(item, mapOf(ShoppingListMerger.key(item.name) to "coulis de tomate")))
    }

    @Test fun epices_au_placard() {
        listOf("graines de cumin", "cumin en poudre", "gingembre en poudre", "piment de cayenne", "mélange 5 baies", "badiane", "curry")
            .forEach { assertTrue(Pantry.isStaple(it), it) }
        listOf("gingembre", "coriandre", "graines de courge").forEach { assertFalse(Pantry.isStaple(it), it) }
    }

    @Test fun ail_bio_frais_avant_chips_et_semoule() = runTest {
        val b = FakeSearch(listOf(
            p("1", "Ail Eco+ Filet 250g"), p("2", "Chips saveur Ail Noir et Crème Fraîche Tyrrells"),
            p("3", "Ail blanc bio Bio Village 250g"), p("4", "Ail semoule Sainte Lucie Pot 320g"), p("5", "Persillade Rustica Ail persil"),
        ))
        val line = CartPlanner.searchLine(b, ShoppingItem(name = "Ail"), preferOrganic = true)
        assertEquals("3", line.product?.id)
        assertNull(line.note)
        assertEquals(listOf("2", "4", "5"), line.candidates.takeLast(3).map { it.id }.sorted())
        val classic = CartPlanner.searchLine(b, ShoppingItem(name = "Ail"), preferOrganic = false)
        assertEquals("1", classic.product?.id)
    }
}

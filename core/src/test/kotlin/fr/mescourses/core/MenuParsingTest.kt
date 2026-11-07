package fr.mescourses.core

import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.menu.MenuConfig
import fr.mescourses.core.menu.MenuPrompt
import fr.mescourses.core.menu.MenuTextParser
import fr.mescourses.core.menu.RecipeResponseParser
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.season.Seasonality
import java.time.LocalDate
import java.time.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuParsingTest {

    @Test fun menu_en_texte() {
        val text = """Voici ma proposition :
            1. **Velouté de potimarron** — doux et rapide
            2) Curry de pois chiches : au lait de coco
            - Poisson en papillote - avec des poireaux
            Bon appétit !"""
        assertEquals(
            listOf(
                "Velouté de potimarron" to "doux et rapide",
                "Curry de pois chiches" to "au lait de coco",
                "Poisson en papillote" to "avec des poireaux",
            ),
            MenuTextParser.parse(text).map { it.title to it.description },
        )
    }

    @Test fun ingredients_principaux_dans_la_ligne() {
        val d = MenuTextParser.parseLine("Tarte aux poireaux — pâte maison — ingrédients : poireaux, crème fraîche, œufs et comté")
        assertEquals("Tarte aux poireaux", d.title)
        assertEquals("pâte maison", d.description)
        assertEquals(listOf("poireaux", "crème fraîche", "œufs", "comté"), d.ingredients)
        val sansDescription = MenuTextParser.parseLine("Dahl de lentilles (ingrédients principaux : lentilles corail, lait de coco)")
        assertEquals("Dahl de lentilles", sansDescription.title)
        assertEquals(listOf("lentilles corail", "lait de coco"), sansDescription.ingredients)
    }

    @Test fun demande_de_recette_reutilise_les_achats() {
        val req = MenuPrompt.recipeRequest(
            fr.mescourses.core.model.MenuDish("Gratin", "", Portions(2), mainIngredients = listOf("chou-fleur")),
            4, listOf("crème fraîche", "persil"),
        )
        assertTrue("chou-fleur" in req && "à réutiliser en priorité" in req && "crème fraîche, persil" in req)
    }

    @Test fun recette_en_texte_si_pas_de_json() {
        val r = RecipeResponseParser.parse(
            "Ingrédients :\n- 200 g de lentilles\n- 1 oignon\nÉtapes :\n1. Rincer\n2. Cuire 20 min",
            "Dahl", 4,
        )!!
        assertEquals(2, r.ingredients.size)
        assertEquals(listOf("Rincer", "Cuire 20 min"), r.steps)
    }

    @Test fun saisons() {
        val nov = Seasonality.forMonth(Month.NOVEMBER)
        assertTrue("poireau" in nov.vegetables && "potiron" in nov.vegetables)
        assertFalse("tomate" in nov.vegetables)
        assertTrue("tomate" in Seasonality.forMonth(Month.JULY).vegetables)
        assertTrue("pomme" in nov.fruits && "fraise" !in nov.fruits)
    }

    @Test fun prompt_contient_regles_favoris_bannis_et_saison() {
        val cookbook = listOf(
            Recipe(title = "Gratin dauphinois", status = RecipeStatus.FAVORI),
            Recipe(title = "Lasagnes", status = RecipeStatus.BANNI),
            Recipe(title = "Hachis parmentier", lastCookedAt = "2026-11-01"),
            Recipe(title = "Vieux plat", lastCookedAt = "2026-01-01"),
        )
        val p = MenuPrompt.system(LocalDate.of(2026, 11, 7), MenuConfig(5, Portions(2, 1), "rapide"), cookbook)
        assertTrue("bio" in p && "saison" in p && "sains" in p)
        assertTrue("Mutualise les ingrédients" in p && "ingrédients : a, b, c" in p)
        assertTrue("poireau" in p)
        assertTrue("NE JAMAIS proposer : Lasagnes" in p)
        assertTrue("favoris" in p && "Gratin dauphinois" in p)
        assertTrue("Hachis parmentier" in p && "Vieux plat" !in p)
        assertTrue("5 repas" in p && "2 adultes · 1 enfant" in p)
    }

    @Test fun portions_enfants() {
        assertEquals(3.0, Portions(2, 2).equivalent())
        assertEquals("1 adulte", Portions(1).label())
    }

    @Test fun panier_privilegie_le_bio() {
        val item = ShoppingItem(name = "Carottes")
        assertEquals("Carottes bio", CartPlanner.searchQuery(item, preferOrganic = true))
        val ranked = CartPlanner.rank(
            item,
            listOf(Product("1", "Carottes 1kg"), Product("2", "Carottes bio 1kg"), Product("3", "Chips")),
            preferOrganic = true,
        )
        assertEquals(listOf("2", "1", "3"), ranked.map { it.id })
    }
}

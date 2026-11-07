package fr.mescourses.core

import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.PlateBalance
import fr.mescourses.core.menu.PlateBalance.Component.FECULENT
import fr.mescourses.core.menu.PlateBalance.Component.LEGUMES
import fr.mescourses.core.menu.PlateBalance.Component.PROTEINE
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.shopping.Aisle
import fr.mescourses.core.visual.DishVisuals
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceVisualsAislesTest {

    @Test fun assiette_complete_ou_incomplete() {
        assertEquals(emptyList(), PlateBalance.missing(listOf("Saumon", "poireaux", "riz complet")))
        assertEquals(listOf(PROTEINE, FECULENT), PlateBalance.missing(listOf("Velouté de potiron")))
        assertEquals(setOf(PROTEINE, LEGUMES, FECULENT), PlateBalance.components(listOf("Dahl de lentilles corail", "épinards", "riz basmati")))
        // « ble » ne doit pas être trouvé dans « blettes »
        assertEquals(setOf(LEGUMES), PlateBalance.components(listOf("blettes")))
        assertEquals(setOf(PROTEINE, LEGUMES, FECULENT), PlateBalance.components(listOf("Omelette", "petits pois", "pommes de terre")))
    }

    @Test fun bilan_d_equilibre_du_menu() {
        val dishes = listOf(
            MenuDish("Saumon et riz", "", Portions(2), mainIngredients = listOf("saumon", "brocoli", "riz")),
            MenuDish("Velouté de potiron", "", Portions(2), mainIngredients = listOf("potiron", "oignon")),
        )
        val s = PlateBalance.menuSummary(dishes)!!
        assertTrue("plat 2 sans protéine ni féculent" in s, s)
        assertTrue("plat 1" !in s)
    }

    @Test fun relance_si_la_recette_n_est_pas_equilibree() = runTest {
        val prompts = mutableListOf<String>()
        val llm = TextGenerator { _, p ->
            prompts += p
            if ("IMPORTANT" in p) """{"ingredients":["600 g de potiron","4 œufs","200 g de pain complet"],"etapes":["Cuire"]}"""
            else """{"ingredients":["600 g de potiron","1 oignon"],"etapes":["Cuire"]}"""
        }
        val recipes = InMemoryRecipes()
        val draft = InMemoryDraft()
        draft.set(listOf(MenuDish("Velouté de potiron", "", Portions(2))))
        MenuValidator(llm, recipes, InMemoryShopping(), InMemoryPlan(), draft, { 0.5 }).validate()
        assertEquals(2, prompts.size)
        assertTrue("n'avait pas une protéine ni un féculent" in prompts[1], prompts[1])
        assertEquals(3, recipes.state.value.single().ingredients.size)
    }

    @Test fun visuels_des_plats() {
        assertEquals("🍲", DishVisuals.of("Velouté de poireaux").emoji)
        assertEquals("🥧", DishVisuals.of("Tarte aux poireaux").emoji)
        assertEquals(DishVisuals.Category.POISSON, DishVisuals.of("Pavé de saumon, riz complet").category)
        assertEquals("🍛", DishVisuals.of("Dahl de lentilles corail").emoji)
        assertEquals("🍝", DishVisuals.of("Pâtes au pesto").emoji)
        assertEquals("🐟", DishVisuals.of("Papillote du marché", listOf("cabillaud", "fenouil")).emoji)
        assertEquals("🍽", DishVisuals.of("Assiette surprise").emoji)
    }

    @Test fun rayons() {
        assertEquals(Aisle.FRUITS_LEGUMES, Aisle.of("Pommes de terre"))
        assertEquals(Aisle.CREMERIE, Aisle.of("Crème fraîche"))
        assertEquals(Aisle.EPICERIE, Aisle.of("Lait de coco"))
        assertEquals(Aisle.CREMERIE, Aisle.of("Lait demi-écrémé"))
        assertEquals(Aisle.POISSONNERIE, Aisle.of("Pavé de saumon"))
        assertEquals(Aisle.EPICERIE, Aisle.of("Huile d'olive"))
        assertEquals(Aisle.MAISON, Aisle.of("Lessive"))
        assertEquals(Aisle.FRUITS_LEGUMES, Aisle.of("Persil"))
        assertEquals(Aisle.AUTRES, Aisle.of("Truc inconnu"))
        assertTrue(Aisle.FRUITS_LEGUMES.ordinal < Aisle.MAISON.ordinal)
    }
}

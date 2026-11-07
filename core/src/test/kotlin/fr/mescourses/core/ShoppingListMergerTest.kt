package fr.mescourses.core

import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.ItemSource
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlin.test.Test
import kotlin.test.assertEquals

class ShoppingListMergerTest {

    private val crepes = Recipe(
        title = "Crêpes", servings = 4,
        ingredients = IngredientParser.parseAll(listOf("250 g de farine", "4 œufs", "50 cl de lait", "1 pincée de sel")),
    )
    private val gateau = Recipe(
        title = "Gâteau", servings = 6,
        ingredients = IngredientParser.parseAll(listOf("1 kg de farine", "3 oeufs", "0,5 l de lait")),
    )

    @Test fun mise_a_l_echelle() {
        val items = ShoppingListMerger.fromRecipe(crepes, servings = 2)
        assertEquals(125.0, items.first { it.name == "farine" }.quantity)
        assertEquals(2.0, items.first { it.name == "œufs" }.quantity)
    }

    @Test fun fusion_des_doublons_et_conversion() {
        val merged = ShoppingListMerger.merge(
            ShoppingListMerger.fromRecipe(crepes, 4) + ShoppingListMerger.fromRecipe(gateau, 6) +
                ShoppingListMerger.fromText("lessive"),
        )
        val farine = merged.first { it.name == "Farine" }
        assertEquals(1.25, farine.quantity); assertEquals("kg", farine.unit)
        val oeufs = merged.first { ShoppingListMerger.key(it.name) == "oeuf" }
        assertEquals(7.0, oeufs.quantity)
        val lait = merged.first { it.name == "Lait" }
        assertEquals(1.0, lait.quantity); assertEquals("l", lait.unit)
        assertEquals("Crêpes, Gâteau", farine.origin)
        assertEquals(ItemSource.MANUEL, merged.first { it.name == "Lessive" }.source)
        assertEquals(5, merged.size) // farine, œufs, lait, sel, lessive
    }

    @Test fun unites_incompatibles_restent_separees() {
        val merged = ShoppingListMerger.merge(
            listOf(ShoppingItem(name = "citron", quantity = 2.0), ShoppingItem(name = "citrons", quantity = 1.0, unit = "c. à soupe")),
        )
        assertEquals(2, merged.size)
    }

    @Test fun articles_coches_ignores() {
        val merged = ShoppingListMerger.merge(
            listOf(ShoppingItem(name = "pain", checked = true), ShoppingItem(name = "beurre")),
        )
        assertEquals(listOf("Beurre"), merged.map { it.name })
    }

    @Test fun arrondi_des_pieces() {
        val merged = ShoppingListMerger.merge(listOf(ShoppingItem(name = "oignon", quantity = 1.5)))
        assertEquals(2.0, merged.single().quantity)
    }

    @Test fun formatage() {
        assertEquals("1,25 kg Farine", ShoppingListMerger.format(ShoppingItem(name = "Farine", quantity = 1.25, unit = "kg")))
        assertEquals("Sel", ShoppingListMerger.format(ShoppingItem(name = "Sel")))
    }
}

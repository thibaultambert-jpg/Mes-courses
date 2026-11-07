package fr.mescourses.core

import fr.mescourses.core.ingredients.IngredientParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngredientParserTest {
    private fun check(line: String, q: Double?, unit: String?, name: String) {
        val i = IngredientParser.parse(line)
        assertEquals(q, i.quantity, "quantité de « $line »")
        assertEquals(unit, i.unit, "unité de « $line »")
        assertEquals(name, i.name, "nom de « $line »")
    }

    @Test fun grammes() = check("200 g de farine", 200.0, "g", "farine")
    @Test fun kilos() = check("1,5 kg de pommes de terre", 1.5, "kg", "pommes de terre")
    @Test fun oeufs() = check("3 œufs", 3.0, null, "œufs")
    @Test fun cuillereSoupe() = check("2 c. à soupe d'huile d'olive", 2.0, "c. à soupe", "huile d'olive")
    @Test fun cuillereCafeAbrege() = check("1 cac de cumin", 1.0, "c. à café", "cumin")
    @Test fun fraction() = check("1/2 citron", 0.5, null, "citron")
    @Test fun fractionUnicode() = check("½ botte de persil", 0.5, "botte", "persil")
    @Test fun gousses() = check("2 gousses d'ail", 2.0, "gousse", "ail")
    @Test fun centilitres() = check("20 cl de crème fraîche", 20.0, "cl", "crème fraîche")
    @Test fun motNombre() = check("une pincée de sel", 1.0, "pincée", "sel")
    @Test fun parentheses() = check("150 g de lardons (fumés)", 150.0, "g", "lardons")
    @Test fun plage() = check("2-3 tomates", 3.0, null, "tomates")

    @Test fun sansQuantite() {
        val i = IngredientParser.parse("Sel, poivre")
        assertNull(i.quantity)
        assertNull(i.unit)
        assertEquals("Sel, poivre", i.name)
    }

    @Test fun articleNonUnite() = check("l'huile de friture", null, null, "l'huile de friture")
}

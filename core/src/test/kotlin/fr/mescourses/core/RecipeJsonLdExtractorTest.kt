package fr.mescourses.core

import fr.mescourses.core.recipes.RecipeJsonLdExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecipeJsonLdExtractorTest {
    private val html = javaClass.getResource("/marmiton_like.html")!!.readText()

    @Test fun extrait_la_recette_du_graph() {
        val r = assertNotNull(RecipeJsonLdExtractor.extract(html, "https://exemple.fr/quiche"))
        assertEquals("Quiche lorraine & facile", r.title)
        assertEquals(6, r.servings)
        assertEquals(6, r.ingredients.size)
        assertEquals(200.0, r.ingredients[1].quantity)
        assertEquals("lardons", r.ingredients[1].name)
        assertEquals(4, r.steps.size)
        assertEquals("Battre les œufs avec la crème et le lait, saler, poivrer.", r.steps[2])
        assertEquals(15, r.prepMinutes)
        assertEquals(45, r.cookMinutes)
        assertEquals("https://exemple.fr/quiche.jpg", r.imageUrl)
        assertEquals(listOf("Plat principal", "quiche", "lardons", "facile"), r.tags)
    }

    @Test fun page_sans_recette() {
        assertNull(RecipeJsonLdExtractor.extract("<html><body>rien</body></html>"))
    }

    @Test fun durees() {
        assertEquals(90, RecipeJsonLdExtractor.parseIsoDurationMinutes("PT1H30M"))
        assertEquals(null, RecipeJsonLdExtractor.parseIsoDurationMinutes("PT0M"))
    }
}

package fr.mescourses.core

import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeFilters
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeFiltersTest {

    private fun hit(title: String, minutes: Int? = null, rating: Double? = null) =
        RecipeHit(title, "https://exemple.invalid/" + title.hashCode(), "s", "Site", totalMinutes = minutes, rating = rating)

    private val hits = listOf(
        hit("Poulet rôti aux herbes", 75, 4.5),
        hit("Saumon en papillote", 25, 4.8),
        hit("Salade de lentilles, vinaigrette à la moutarde", 15, 4.0),
        hit("Tarte aux poireaux et lardons", 50, 4.2),
        hit("Gratin de courgettes", null, 3.9),
        hit("Moules marinières au vin blanc", 30, 4.6),
        hit("Crevettes sautées à l'ail", 12, 4.1),
    )

    @Test fun requete_avec_les_filtres_seuls() {
        assertEquals("dessert rapide", RecipeFilters.query("", setOf(RecipeFilter.MAX_20, RecipeFilter.DESSERT)))
        assertEquals("potimarron soupe", RecipeFilters.query(" potimarron ", setOf(RecipeFilter.SOUPE)))
        assertEquals("", RecipeFilters.query("", setOf(RecipeFilter.MAX_45)))
    }

    @Test fun un_seul_type_de_plat() {
        val s = RecipeFilters.toggle(setOf(RecipeFilter.ENTREE, RecipeFilter.POISSON), RecipeFilter.DESSERT)
        assertEquals(setOf(RecipeFilter.DESSERT, RecipeFilter.POISSON), s)
        assertEquals(setOf(RecipeFilter.POISSON), RecipeFilters.toggle(s, RecipeFilter.DESSERT))
    }

    @Test fun vegetarien_et_halal() {
        assertEquals(
            listOf("Salade de lentilles, vinaigrette à la moutarde", "Gratin de courgettes"),
            RecipeFilters.apply(hits, setOf(RecipeFilter.VEGETARIEN)).map { it.title },
        )
        // Halal : ni lardons ni vin ; « vinaigrette » n'est pas du vin.
        val halal = RecipeFilters.apply(hits, setOf(RecipeFilter.HALAL)).map { it.title }
        assertEquals(5, halal.size)
        assert("Salade de lentilles, vinaigrette à la moutarde" in halal)
    }

    @Test fun ingredient_principal_temps_et_tri() {
        assertEquals(listOf("Saumon en papillote"), RecipeFilters.apply(hits, setOf(RecipeFilter.POISSON)).map { it.title })
        assertEquals(
            listOf("Moules marinières au vin blanc", "Crevettes sautées à l'ail"),
            RecipeFilters.apply(hits, setOf(RecipeFilter.FRUITS_DE_MER)).map { it.title },
        )
        // ≤ 20 min : durée inconnue gardée.
        assertEquals(
            listOf("Salade de lentilles, vinaigrette à la moutarde", "Gratin de courgettes", "Crevettes sautées à l'ail"),
            RecipeFilters.apply(hits, setOf(RecipeFilter.MAX_20)).map { it.title },
        )
        assertEquals("Saumon en papillote", RecipeFilters.apply(hits, emptySet(), RecipeSort.NOTE).first().title)
        assertEquals("Crevettes sautées à l'ail", RecipeFilters.apply(hits, emptySet(), RecipeSort.RAPIDE).first().title)
    }

    @Test fun pastilles() {
        assertEquals(RecipeFilter.VOLAILLE, RecipeFilters.badge("Poulet rôti aux herbes"))
        assertEquals(RecipeFilter.DESSERT, RecipeFilters.badge("Fondant au chocolat"))
        assertNull(RecipeFilters.badge("Recette mystère"))
    }
}

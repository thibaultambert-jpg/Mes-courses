package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogQuery
import fr.mescourses.core.catalog.CatalogRanking
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.recipes.RecipeFilter
import fr.mescourses.core.recipes.RecipeHit
import fr.mescourses.core.recipes.RecipeFilters
import fr.mescourses.core.recipes.RecipeRating
import fr.mescourses.core.recipes.RecipeSort
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Classement « Pertinence » du catalogue et note pondérée : aucun site ne doit écraser les autres. */
class CatalogRankingTest {

    private fun e(site: String, title: String, rating: Double?, count: Int?, course: Course = Course.PLAT) =
        CatalogEntry(url = "https://$site.fr/${title.replace(' ', '-')}", siteId = site, title = title,
            ingredients = listOf("a", "b"), rating = rating, ratingCount = count, course = course)

    @Test fun note_ponderee_par_le_nombre_d_avis() {
        assertTrue(RecipeRating.score(4.7, 500) > RecipeRating.score(5.0, 2))
        assertEquals(RecipeRating.PRIOR, RecipeRating.score(null, null))
        val hits = listOf(
            RecipeHit("Peu d'avis", "u1", "a", "A", rating = 5.0, ratingCount = 1),
            RecipeHit("Beaucoup d'avis", "u2", "b", "B", rating = 4.6, ratingCount = 800),
        )
        assertEquals("Beaucoup d'avis", RecipeFilters.apply(hits, emptySet(), RecipeSort.NOTE).first().title)
    }

    @Test fun le_filtre_plat_sans_mot_cle_melange_les_sites() = runBlocking {
        // HelloFresh : beaucoup de plats avec énormément d'avis ; les autres sites, peu d'avis.
        val hello = (1..50).map { e("hellofresh", "Plat hello $it", 4.0, 3000 + it) }
        val marmiton = (1..10).map { e("marmiton", "Plat marmiton $it", 4.8, 40 + it) }
        val jow = (1..10).map { e("jow", "Plat jow $it", null, null) }
        val store = InMemoryCatalog(hello + marmiton + jow)
        val res = CatalogRanking.search(store, CatalogQuery.of("", setOf(RecipeFilter.PLAT), 11))
        assertEquals(setOf("hellofresh", "jow", "marmiton"), res.take(3).map { it.siteId }.toSet())
        assertEquals(70, res.size)
    }

    @Test fun le_titre_passe_avant_les_ingredients() {
        val inTitle = e("a", "Gratin de courgette", 3.5, 2)
        val inIngredients = e("b", "Tian provençal", 4.9, 900)
        val res = CatalogRanking.rank(listOf(listOf(inTitle), listOf(inIngredients)), listOf("courgette"))
        assertEquals(inTitle, res.first())
    }

    @Test fun un_tirage_change_la_liste_et_garde_tous_les_sites() = runBlocking {
        val store = InMemoryCatalog(
            (1..200).map { e("marmiton", "Plat marmiton $it", 4.8, 40 + it) } +
                (1..200).map { e("jow", "Plat jow $it", 4.7, 30 + it) } +
                (1..3).map { e("jow", "Gratin de courgette $it", 4.7, 30) },
        )
        val q = CatalogQuery.of("", setOf(RecipeFilter.PLAT), 11, limit = 20)
        val a = CatalogRanking.search(store, q, seed = 7).map { it.title }
        val b = CatalogRanking.search(store, q, seed = 12345).map { it.title }
        assertEquals(a, CatalogRanking.search(store, q, seed = 7).map { it.title })
        assertTrue(a != b)
        assertTrue(a.take(4).any { "marmiton" in it } && a.take(4).any { "jow" in it })
        // Les recettes qui ont le mot dans leur titre restent en tête.
        val c = CatalogRanking.search(store, CatalogQuery.of("courgette", emptySet(), 11, limit = 20), seed = 7)
        assertTrue(c.first().title.startsWith("Gratin de courgette"))
    }
}

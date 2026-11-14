package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogQuery
import fr.mescourses.core.catalog.CatalogRanking
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.Int8Vectors
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

    // --- Recherche par vecteur ---

    /** Vecteur à l'angle [deg] (degrés) de la requête [axis], dans le plan des deux premières dimensions. */
    private fun at(deg: Double): ByteArray {
        val v = FloatArray(Int8Vectors.DIM)
        v[0] = kotlin.math.cos(Math.toRadians(deg)).toFloat()
        v[1] = kotlin.math.sin(Math.toRadians(deg)).toFloat()
        return Int8Vectors.quantize(v)
    }

    private val axis = at(0.0)

    /**
     * Deux sites ; sur chacun, la proximité sémantique décroît quand la note monte (ordres opposés). Une fiche sans
     * vecteur et une fiche d'un autre type.
     */
    private fun vectorStore(withVectors: Boolean = true): InMemoryCatalog {
        fun site(id: String, base: Double) = (0..4).map { j ->
            e(id, "Plat $id $j", base + j * 0.1, 50 + j * 10).let {
                if (withVectors) it.copy(embedding = Int8Vectors.encode(at(10.0 + j * 15))) else it
            }
        }
        return InMemoryCatalog(
            site("aaa", 4.0) + site("bbb", 3.5) +
                e("aaa", "Plat aaa sans vecteur", 5.0, 5000) +
                e("bbb", "Dessert bbb", 4.0, 10, Course.DESSERT).let { if (withVectors) it.copy(embedding = Int8Vectors.encode(axis)) else it },
        )
    }

    @Test fun avec_vecteur_l_ordre_semantique_de_chaque_site_est_garde() = runBlocking {
        val store = vectorStore()
        val q = CatalogQuery(courses = setOf(Course.PLAT), limit = 20, vector = axis)
        val res = CatalogRanking.search(store, q).map { it.title }
        // Rang par rang, chaque site dans son ordre sémantique ; à rang égal, le mieux noté (aaa) d'abord.
        assertEquals((0..4).flatMap { listOf("Plat aaa $it", "Plat bbb $it") }, res)
        // Le même ordre avec un tirage : la proximité prime, le hasard ne départage que les sites.
        val seeded = CatalogRanking.search(store, q, seed = 3).map { it.title }
        (0..4).forEach { j -> assertEquals(setOf("Plat aaa $j", "Plat bbb $j"), seeded.subList(2 * j, 2 * j + 2).toSet()) }
        // Le bonus « tous les mots dans le titre » est gardé : la fiche la plus éloignée qui a « 4 » remonte.
        val store2 = InMemoryCatalog(store.entries.values.map { if (it.title == "Plat bbb 4") it.copy(title = "Plat bbb gratin 4") else it }
            .map { it.copy(keys = listOf("gratin")) })
        val withWords = CatalogRanking.search(store2, q.copy(words = listOf("gratin")))
        assertEquals("Plat bbb gratin 4", withWords.first().title)
        assertEquals((0..4).map { "Plat aaa $it" }, withWords.filter { it.siteId == "aaa" }.map { it.title })
    }

    @Test fun sans_vecteur_rien_ne_change() = runBlocking {
        val q = CatalogQuery(courses = setOf(Course.PLAT), limit = 20)
        val withVectors = vectorStore()
        val without = vectorStore(withVectors = false)
        val res = CatalogRanking.search(withVectors, q).map { it.title }
        assertEquals(CatalogRanking.search(without, q).map { it.title }, res)
        // Par note pondérée, rang par rang.
        val expected = listOf("Plat aaa sans vecteur", "Plat bbb 4") +
            listOf("Plat aaa 4", "Plat bbb 3", "Plat aaa 3", "Plat bbb 2", "Plat aaa 2", "Plat bbb 1", "Plat aaa 1", "Plat bbb 0", "Plat aaa 0")
        assertEquals(expected, res)
        assertEquals(CatalogRanking.search(without, q, seed = 9).map { it.title }, CatalogRanking.search(withVectors, q, seed = 9).map { it.title })
        val lists = listOf(withVectors.entries.values.filter { it.siteId == "aaa" }, withVectors.entries.values.filter { it.siteId == "bbb" })
        assertEquals(CatalogRanking.rank(lists, listOf("plat")), CatalogRanking.rank(lists, listOf("plat"), keepOrder = false))
    }
}

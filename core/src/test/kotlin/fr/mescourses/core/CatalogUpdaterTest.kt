package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogRationalizer
import fr.mescourses.core.catalog.CatalogSource
import fr.mescourses.core.catalog.CatalogUpdater
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.RationalizationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** La rationalisation (lot 2.3) appliquée aux fiches lues par le téléphone. */
class CatalogUpdaterTest {
    private val source = CatalogSource("cuisineaz", listOf("https://x.fr/s.xml"), null, Regex("""cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"""))
    private val urlBonne = "https://www.cuisineaz.com/recettes/bonne-1.aspx"
    private val urlMauvaise = "https://www.cuisineaz.com/recettes/mauvaise-2.aspx"

    private fun page(title: String, rating: String, vararg ingredients: String) = """<html><head>
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"Recipe","name":"$title",
        "recipeCategory":"Plat principal",
        "aggregateRating":{"@type":"AggregateRating","ratingValue":$rating,"reviewCount":"50"},
        "image":["https://img.example/$title.jpg"],"recipeYield":"4 personnes","prepTime":"PT15M","totalTime":"PT45M",
        "recipeIngredient":[${ingredients.joinToString(",") { "\"$it\"" }}]}</script></head></html>"""

    private val site = mapOf(
        "https://x.fr/s.xml" to """<urlset><url><loc>$urlBonne</loc><lastmod>2026-11-08</lastmod></url>
            <url><loc>$urlMauvaise</loc><lastmod>2026-11-09</lastmod></url></urlset>""",
        urlBonne to page("Poulet rôti", "4.8", "1 poulet", "2 carottes", "1 oignon"),
        urlMauvaise to page("Plat moyen", "3.0", "1 poulet", "2 carottes", "1 oignon"),
    )

    private fun updater(store: InMemoryCatalog, fetched: MutableList<String> = mutableListOf(), config: RationalizationConfig = RationalizationConfig()) =
        CatalogUpdater(store, { url -> fetched += url; site[url] ?: error("404") }, listOf(source), now = { 0L }, config = config)

    @Test fun fiche_rejetee_par_la_rationalisation_n_est_pas_inseree() = kotlinx.coroutines.test.runTest {
        val store = InMemoryCatalog(emptyList())
        val fetched = mutableListOf<String>()
        val report = updater(store, fetched).update()
        assertEquals(1, report.added)
        assertEquals(1, report.rejected)
        assertNull(store.byUrl(urlMauvaise))
        // La fiche écartée est retenue comme lue : elle n'est pas relue le lendemain.
        fetched.clear()
        val again = updater(store, fetched).update()
        assertEquals(0, again.added)
        assertEquals(listOf("https://x.fr/s.xml"), fetched)
    }

    @Test fun fiche_acceptee_inseree_avec_ses_tags() = kotlinx.coroutines.test.runTest {
        val store = InMemoryCatalog(emptyList())
        updater(store).update()
        val e = assertNotNull(store.byUrl(urlBonne))
        assertEquals(Course.PLAT, e.course)
        assertTrue(e.keys.isNotEmpty())
        assertEquals(e, CatalogRationalizer.filterSingle(e))
    }

    @Test fun config_permissive_laisse_tout_passer() = kotlinx.coroutines.test.runTest {
        val store = InMemoryCatalog(emptyList())
        val permissive = RationalizationConfig(requireTime = false, keepUnrated = true, minIngredients = 0, defaultMinRating = 0.0)
        val report = updater(store, config = permissive).update()
        assertEquals(2, report.added)
        assertEquals(0, report.rejected)
        assertNotNull(store.byUrl(urlMauvaise))
    }
}

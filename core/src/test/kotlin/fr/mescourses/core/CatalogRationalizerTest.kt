package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogRationalizer
import fr.mescourses.core.catalog.CatalogRationalizer.Reason
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.RationalizationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRationalizerTest {
    private fun e(
        n: Int = 1, site: String = "marmiton", title: String = "Gratin $n", image: String? = "https://img/$n.jpg",
        ingredients: Int = 4, total: Int? = 30, rating: Double? = 4.8, count: Int? = 50, course: Course = Course.PLAT,
    ) = CatalogEntry(
        url = "https://www.$site.fr/recettes/r-$n.html", siteId = site, title = title, imageUrl = image,
        ingredients = List(ingredients) { "ingrédient $it" }, totalMinutes = total, rating = rating, ratingCount = count, course = course,
    )

    private val cfg = RationalizationConfig()
    private fun reason(x: CatalogEntry, c: RationalizationConfig = cfg) = CatalogRationalizer.reject(x, c, mutableSetOf())

    @Test fun rejet_sans_temps() {
        assertEquals(Reason.TIME, reason(e(total = null)))
        assertNull(CatalogRationalizer.filterSingle(e(total = null)))
        assertNotNull(CatalogRationalizer.filterSingle(e(total = null).copy(prepMinutes = 10, cookMinutes = 5)))
    }

    @Test fun rejet_note_trop_basse_selon_le_site() {
        assertNotNull(CatalogRationalizer.filterSingle(e(site = "hellofresh", rating = 4.2)))
        assertEquals(Reason.RATING, reason(e(site = "marmiton", rating = 4.2)))
    }

    @Test fun rejet_trop_peu_d_avis() {
        assertEquals(Reason.RATING_COUNT, reason(e(count = 3)))
        assertEquals(Reason.RATING_COUNT, reason(e(count = null)))
    }

    @Test fun non_notee_gardee() {
        assertNotNull(CatalogRationalizer.filterSingle(e(rating = null, count = null)))
        assertEquals(Reason.RATING, reason(e(rating = null, count = null), cfg.copy(keepUnrated = false)))
    }

    @Test fun image_en_double_rejetee() {
        val seen = mutableSetOf<String>()
        assertNotNull(CatalogRationalizer.filterSingle(e(1, image = "https://img/same.jpg"), cfg, seen))
        assertNull(CatalogRationalizer.filterSingle(e(2, image = "https://img/same.jpg"), cfg, seen))
        // Une fiche rejetée plus tôt ne consomme pas son image.
        val s2 = mutableSetOf<String>()
        assertNull(CatalogRationalizer.filterSingle(e(3, image = "https://img/z.jpg", total = null), cfg, s2))
        assertTrue(s2.isEmpty())
    }

    @Test fun cours_exclu() {
        assertEquals(Reason.COURSE, reason(e(course = Course.BOISSON)))
        assertNull(reason(e(course = Course.BOISSON), cfg.copy(excludedCourses = emptySet())))
    }

    @Test fun trop_peu_d_ingredients() {
        assertEquals(Reason.INGREDIENTS, reason(e(ingredients = 2)))
        assertEquals(Reason.TITLE, reason(e(title = " ")))
    }

    @Test fun rapport_par_site_et_markdown() {
        val list = listOf(e(1), e(2, total = null), e(3, site = "hellofresh", rating = 4.1), e(4, site = "hellofresh", rating = 3.0), e(5, ingredients = 1))
        val r = CatalogRationalizer.process(list, titleDedupe = { l -> l.filter { !it.url.endsWith("r-3.html") } })
        assertEquals(2, r.report.sites["marmiton"]!!.rejected.values.sum())
        assertEquals(Reason.RATING, r.report.sites["hellofresh"]!!.rejected.keys.single())
        assertEquals(1, r.report.sites["hellofresh"]!!.duplicates)
        assertEquals(1, r.report.sites["marmiton"]!!.kept)
        assertEquals(0, r.report.sites["hellofresh"]!!.kept)
        assertEquals(1, r.kept.size)
        val md = r.report.markdown()
        assertTrue("| marmiton | 3 |" in md && "| hellofresh | 2 |" in md && "**Total** | 5 |" in md, md)
    }

    @Test fun resultat_deterministe() {
        val list = (1..20).map { e(it, site = if (it % 2 == 0) "marmiton" else "hellofresh", image = "https://img/${it % 7}.jpg") }
        val a = CatalogRationalizer.process(list)
        assertEquals(a, CatalogRationalizer.process(list))
        assertEquals(a, CatalogRationalizer.process(list.reversed()))
        assertTrue(a.kept.isNotEmpty())
    }

    @Test fun config_chargee_depuis_json() {
        val c = RationalizationConfig.load("""{"defaultMinRating":4.0,"minRatingBySite":{"x":3.5},"excludedCourses":["DESSERT"],"inconnu":1}""")
        assertEquals(4.0, c.defaultMinRating)
        assertEquals(mapOf("x" to 3.5), c.minRatingBySite)
        assertEquals(setOf(Course.DESSERT), c.excludedCourses)
        assertEquals(10, c.minRatingCount)
    }
}

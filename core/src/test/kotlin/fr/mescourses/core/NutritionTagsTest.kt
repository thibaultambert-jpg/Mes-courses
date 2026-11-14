package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.NutritionTag
import fr.mescourses.core.catalog.NutritionTags
import fr.mescourses.core.catalog.recompute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NutritionTagsTest {
    private fun tags(title: String, keys: List<String>, course: Course = Course.PLAT, minutes: Int? = null) =
        NutritionTags.of(title, keys, course, minutes)

    @Test fun soupe_sans_creme_est_legere() {
        assertTrue(NutritionTag.LIGHT in tags("Velouté de courgettes", listOf("courgette", "oignon"), Course.SOUPE))
    }

    @Test fun gratin_de_pates_est_reconfortant() {
        assertTrue(NutritionTag.COMFORT in tags("Gratin de pâtes au jambon", listOf("pate", "jambon")))
    }

    @Test fun lentilles_fibres() {
        assertTrue(NutritionTag.HIGH_FIBER in tags("Dahl de lentilles", listOf("lentille", "oignon")))
        assertTrue(NutritionTag.HIGH_FIBER in tags("Poêlée", listOf("brocoli", "epinard")))
        assertFalse(NutritionTag.HIGH_FIBER in tags("Poêlée", listOf("brocoli", "oignon")))
    }

    @Test fun saumon_omega3() {
        assertTrue(NutritionTag.OMEGA3 in tags("Pavé de saumon", listOf("saumon", "citron")))
        assertFalse(NutritionTag.OMEGA3 in tags("Poulet rôti", listOf("poulet")))
    }

    @Test fun dessert_jamais_leger() {
        assertFalse(NutritionTag.LIGHT in tags("Salade de fruits légère", listOf("pomme", "orange"), Course.DESSERT))
    }

    @Test fun creme_et_lardons_retirent_leger() {
        val s = tags("Soupe de potiron", listOf("potiron", "creme", "lardon"), Course.SOUPE)
        assertFalse(NutritionTag.LIGHT in s)
        assertTrue(NutritionTag.COMFORT in s)
        assertTrue(NutritionTag.LIGHT in tags("Soupe de potiron", listOf("potiron", "creme"), Course.SOUPE))
    }

    @Test fun plat_long_est_reconfortant() {
        assertTrue(NutritionTag.COMFORT in tags("Rôti de veau", listOf("veau"), Course.PLAT, 120))
        assertFalse(NutritionTag.COMFORT in tags("Rôti de veau", listOf("veau"), Course.PLAT, 45))
    }

    @Test fun fiche_du_catalogue_porte_ses_tags() {
        val e = CatalogEntry(
            url = "https://www.cuisineaz.com/recettes/x-1.aspx", siteId = "cuisineaz", title = "Salade de lentilles au saumon",
            ingredients = listOf("200 g de lentilles", "2 pavés de saumon", "1 oignon"), category = "Salade",
        ).recompute()
        assertEquals(setOf(NutritionTag.LIGHT, NutritionTag.HIGH_FIBER, NutritionTag.OMEGA3), e.nutritionTags)
    }
}

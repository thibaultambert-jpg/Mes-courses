package fr.mescourses.core

import fr.mescourses.core.recipes.PageFetcher
import fr.mescourses.core.recipes.RecipeImporter
import fr.mescourses.core.recipes.RecipeSearchParser
import fr.mescourses.core.recipes.RecipeSearcher
import fr.mescourses.core.recipes.RecipeSites
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeSearchTest {
    private val marmiton = RecipeSites.byId("marmiton")!!
    private val page = javaClass.getResource("/search/marmiton_like_search.html")!!.readText()
    private val pageUrl = marmiton.searchUrlFor("risotto")!!

    @Test fun lit_les_resultats_sans_dependre_de_la_mise_en_page() {
        val hits = RecipeSearchParser.parse(page, pageUrl, marmiton)
        assertEquals(
            listOf("Risotto aux champignons", "Risotto au potimarron", "Risotto aux asperges vertes"),
            hits.map { it.title },
        )
        val champi = hits[0]
        assertEquals("https://www.marmiton.org/recettes/recette_risotto-aux-champignons_12345.aspx?utm=x", champi.url)
        assertEquals("https://img.example/champi-320.jpg", champi.imageUrl)
        assertEquals(4.8, champi.rating)
        assertEquals(1204, champi.ratingCount)
        assertEquals(45, champi.totalMinutes)
        val poti = hits[1]
        assertEquals("https://img.example/poti.jpg", poti.imageUrl)
        assertEquals(70, poti.totalMinutes)
        assertEquals(4.5, poti.rating)
        // Titre et image venus de la liste JSON-LD (le lien de la page ne dit que « Voir la recette »).
        assertEquals("https://img.example/asperges.jpg", hits[2].imageUrl)
        assertTrue(hits.all { it.siteName == "Marmiton" })
    }

    @Test fun adresse_de_recherche_encodee() {
        assertEquals(
            "https://www.marmiton.org/recettes/recherche.aspx?aqt=gratin%20dauphinois%20%C3%A9t%C3%A9",
            marmiton.searchUrlFor(" gratin dauphinois été "),
        )
    }

    @Test fun reconnait_les_sites_et_les_pages_de_recette() {
        assertEquals("cuisineaz", RecipeSites.forUrl("https://www.cuisineaz.com/recettes/hachis-parmentier-56219.aspx")?.id)
        assertEquals("jdf", RecipeSites.forUrl("https://cuisine.journaldesfemmes.fr/recette/315821-risotto")?.id)
        assertNull(RecipeSites.forUrl("https://exemple.fr/recette"))
        assertTrue(RecipeSites.byId("750g")!!.isRecipeUrl("https://www.750g.com/risotto-aux-champignons-r12345.htm"))
        assertTrue(!marmiton.isRecipeUrl("https://www.marmiton.org/recettes/selection_risottos.aspx"))
        assertEquals(4, RecipeSites.searchable.size)
    }

    @Test fun normalise_et_trouve_les_liens() {
        assertEquals(
            RecipeSites.normalize("https://www.marmiton.org/recettes/recette_a_1.aspx?utm=1#avis"),
            RecipeSites.normalize("http://marmiton.org/recettes/recette_a_1.aspx/"),
        )
        assertEquals("https://www.750g.com/x-r1.htm", RecipeSites.findUrl("Regarde ça : https://www.750g.com/x-r1.htm."))
        assertNull(RecipeSites.findUrl("pas de lien"))
    }

    @Test fun idees_de_saison() {
        val octobre = fr.mescourses.core.season.Seasonality.highlights(java.time.Month.OCTOBER)
        assertTrue("courge" in octobre)
        assertTrue("carotte" !in octobre)
    }

    @Test fun durees_et_notes() {
        assertEquals(75, RecipeSearchParser.minutesOf("Préparation 1h15"))
        assertEquals(20, RecipeSearchParser.minutesOf("20 min"))
        assertNull(RecipeSearchParser.ratingOf("8/10"))
    }

    @Test fun user_agent_comme_chrome() {
        val wv = "Mozilla/5.0 (Linux; Android 14; PGT-N19 Build/HONORPGT-N19; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; PGT-N19 Build/HONORPGT-N19) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.58 Mobile Safari/537.36",
            RecipeSites.chromeUserAgent(wv),
        )
    }

    @Test fun parcourir_un_site_ouvre_sa_page_de_recettes() {
        assertEquals("https://www.marmiton.org/recettes/index/categorie/plat-principal/", RecipeSites.byId("marmiton")!!.browsePage)
        assertEquals("https://www.750g.com/home_rubrique_-_recettes.htm", RecipeSites.byId("750g")!!.browsePage)
        assertEquals("https://cuisine.journaldesfemmes.fr/toutes-les-recettes/", RecipeSites.byId("jdf")!!.browsePage)
        // Les recettes ouvertes depuis ces pages sont reconnues (=> version simplifiée).
        listOf(
            "https://www.marmiton.org/recettes/recette_poulet-basquaise_17543.aspx",
            "https://www.750g.com/boeuf-bourguignon-r1234.htm",
            "https://cuisine.journaldesfemmes.fr/recette/315418-boeuf-bourguignon",
        ).forEach { url -> assertTrue(RecipeSites.forUrl(url)?.isRecipeUrl(url) == true, url) }
        // Les pages de liste, elles, ne le sont pas (on peut naviguer dedans).
        RecipeSites.all.map { it.browsePage }
            .forEach { url -> assertTrue(RecipeSites.forUrl(url)?.isRecipeUrl(url) == false, url) }
        // Chaque site a sa page de recettes (plus la page d'accueil).
        assertTrue(RecipeSites.all.all { it.browseUrl != null }, RecipeSites.all.filter { it.browseUrl == null }.joinToString { it.id })
        assertEquals("https://picnic.app/fr/recettes/", RecipeSites.byId("picnic")!!.browsePage)
        assertEquals("https://jow.fr/blog/categories/idees-recettes", RecipeSites.byId("jow")!!.browsePage)
        // Elle : catégorie ≠ recette.
        val elleCat = "https://www.elle.fr/Elle-a-Table/Recettes-de-cuisine/Plats"
        val elleRecipe = "https://www.elle.fr/Elle-a-Table/Recettes-de-cuisine/Gratin-dauphinois-2971574"
        assertTrue(RecipeSites.forUrl(elleCat)?.isRecipeUrl(elleCat) == false)
        assertTrue(RecipeSites.forUrl(elleRecipe)?.isRecipeUrl(elleRecipe) == true)
        val jow = "https://jow.fr/recipes/poulet-tikka-masala-5f8a1b2c3d"
        assertTrue(RecipeSites.forUrl(jow)?.isRecipeUrl(jow) == true)
    }

    @Test fun recettes_populaires_du_moment() = runTest {
        val page = javaClass.getResource("/search/marmiton_like_search.html")!!.readText()
        val asked = mutableListOf<String>()
        val searcher = RecipeSearcher(PageFetcher { url -> asked += url; page })
        val sites = listOfNotNull(RecipeSites.byId("marmiton"), RecipeSites.byId("750g"))
        val result = searcher.popular(sites)
        assertTrue(result.hits.isNotEmpty())
        // 750g a une page dédiée ; Marmiton : sa page d'accueil.
        assertTrue("https://www.750g.com/recettes_populaires.htm" in asked, asked.toString())
        assertTrue("https://www.marmiton.org/" in asked, asked.toString())
    }

    @Test fun melange_les_sites_et_bascule_sur_le_navigateur() = runTest {
        val sites = RecipeSites.searchable.take(2)
        val http = PageFetcher { url -> if ("marmiton" in url) page else error("403") }
        val browser = PageFetcher { url ->
            """<a href="https://www.750g.com/risotto-r1.htm"><h3>Risotto 750g</h3></a>
               <a href="https://www.750g.com/risotto-vert-r2.htm"><h3>Risotto vert</h3></a>"""
        }
        val result = RecipeSearcher(listOf(http, browser)).search("risotto", sites)
        assertEquals(
            listOf("Risotto aux champignons", "Risotto 750g", "Risotto au potimarron", "Risotto vert", "Risotto aux asperges vertes"),
            result.hits.map { it.title },
        )
        assertTrue(result.failures.isEmpty())

        val failing = RecipeSearcher(PageFetcher { error("hors connexion") }).search("risotto", sites)
        assertEquals(setOf("Marmiton", "750g"), failing.failures.keys.map { it.name }.toSet())
    }

    @Test fun import_essaie_le_navigateur_si_le_http_echoue() = runTest {
        val recipePage = javaClass.getResource("/marmiton_like.html")!!.readText()
        val importer = RecipeImporter(listOf(PageFetcher { "<html>vérification anti-robot</html>" }, PageFetcher { recipePage }))
        assertEquals("Quiche lorraine & facile", importer.import("https://exemple.fr/quiche").title)
        assertFailsWith<IllegalStateException> { RecipeImporter(listOf(PageFetcher { "<html></html>" })).import("https://exemple.fr") }
    }
}

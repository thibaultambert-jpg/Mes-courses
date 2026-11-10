package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogPolicy
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.Sitemap
import fr.mescourses.core.catalog.toHit
import fr.mescourses.core.menu.DishKind
import fr.mescourses.core.recipes.RecipeJsonLdExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalogue local de recettes : lecture des sitemaps et des fiches, tri de ce qu'on garde. */
class CatalogTest {

    private fun page(title: String, category: String?, rating: String?, vararg ingredients: String) = """<html><head>
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"Recipe","name":"$title",
        ${if (category != null) "\"recipeCategory\":\"$category\"," else ""}
        ${if (rating != null) "\"aggregateRating\":{\"@type\":\"AggregateRating\",\"ratingValue\":$rating,\"reviewCount\":\"12\"}," else ""}
        "image":["https://img.example/photo.jpg"],"recipeYield":"4 personnes","prepTime":"PT15M","totalTime":"PT45M",
        "recipeIngredient":[${ingredients.joinToString(",") { "\"$it\"" }}],
        "recipeInstructions":[{"@type":"HowToStep","text":"Une étape qu'on ne garde pas."}]}</script></head></html>"""

    private fun entry(title: String, category: String?, rating: String?, vararg ingredients: String): CatalogEntry {
        val url = "https://www.cuisineaz.com/recettes/x-1.aspx"
        return CatalogEntry.of(RecipeJsonLdExtractor.extractDetails(page(title, category, rating, *ingredients), url)!!, "cuisineaz", url, "2026-11-01")
    }

    @Test fun sitemap_index_et_liste_d_adresses() {
        val index = Sitemap.parse("""<?xml version="1.0"?><sitemapindex><sitemap><loc>https://x.fr/recettes-1.xml</loc><lastmod>2026-11-01</lastmod></sitemap>
            <sitemap><loc><![CDATA[https://x.fr/s/?code=a&amp;page=2]]></loc></sitemap></sitemapindex>""")
        assertEquals(listOf("https://x.fr/recettes-1.xml", "https://x.fr/s/?code=a&page=2"), index.children)
        assertTrue(index.pages.isEmpty())
        val list = Sitemap.parse("""<urlset><url><loc>https://x.fr/recettes/a-1.aspx</loc><lastmod>2026-11-09T17:00:19+01:00</lastmod>
            <image:image><image:loc>https://x.fr/a.jpg</image:loc></image:image></url><url><loc> https://x.fr/recettes/b-2.aspx </loc></url></urlset>""")
        assertEquals(listOf("https://x.fr/recettes/a-1.aspx", "https://x.fr/recettes/b-2.aspx"), list.pages.map { it.url })
        assertEquals("2026-11-09T17:00:19+01:00", list.pages[0].lastmod)
        assertNull(list.pages[1].lastmod)
    }

    @Test fun fiche_lue_pour_le_catalogue() {
        val e = entry("Gratin de poireaux au jambon", "Plat principal", "4.26", "4 poireaux", "4 tranches de jambon", "50 cl de lait", "500 g de pommes de terre", "1 pincée de sel")
        assertEquals("cuisineaz.com/recettes/x-1.aspx", e.id)
        assertEquals(Course.PLAT, e.course)
        assertEquals(DishKind.VIANDE, e.kind)
        assertEquals(4.3, e.rating)
        assertEquals(12, e.ratingCount)
        assertEquals(45, e.totalMinutes)
        assertEquals("https://img.example/photo.jpg", e.imageUrl)
        assertEquals(listOf("poireau"), e.produce)
        // Le poireau se trouve de septembre à avril.
        assertTrue(11 in e.months && 1 in e.months && 7 !in e.months, e.months.toString())
        assertTrue(e.balanced)
        assertTrue("poireau" in e.keys && "sel" !in e.keys, e.keys.toString())
        assertTrue("gratin de poireau au jambon" in e.searchText && "pomme de terre" in e.searchText)
        assertEquals("4 poireaux", e.ingredients.first())
        // Recette rendue au reste de l'appli : ingrédients analysés, pas d'étapes.
        val r = e.toRecipe()
        assertEquals(4.0, r.ingredients.first().quantity)
        assertTrue(r.steps.isEmpty())
    }

    @Test fun type_de_recette() {
        assertEquals(Course.DESSERT, entry("The Basilic Monster", "Gâteaux", "5", "200 g de farine", "3 œufs").course)
        assertEquals(Course.ENTREE, entry("Œufs cocotte aux asperges", "Entrées", "5", "4 œufs", "1 botte d'asperges").course)
        assertEquals(Course.SOUPE, entry("Velouté de potimarron", null, "4.5", "1 potimarron", "1 oignon").course)
        assertEquals(Course.DESSERT, entry("Tarte à la rhubarbe : la recette facile", "Tarte à la rhubarbe classique", "4.2", "500 g de rhubarbe", "1 pâte brisée").course)
        // Catégorie muette (« Autre ») : l'assiette décide.
        assertEquals(Course.PLAT, entry("Saumon aux endives", "Autre", "4.8", "4 pavés de saumon", "6 endives", "200 g de riz").course)
        assertEquals(Course.AUTRE, entry("Nectarine rôtie", "Autre", "4.8", "4 nectarines", "1 c. à soupe de miel").course)
    }

    @Test fun toute_fiche_lisible_entre_au_catalogue() {
        val good = entry("Curry de pois chiches", "Plat", "4.5", "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz")
        val poor = entry("Curry de pois chiches", "Plat", "3.2", "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz")
        val unrated = entry("Curry de pois chiches", "Plat", null, "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz")
        val dessert = entry("Mousse au chocolat", "Dessert", "4.9", "200 g de chocolat", "6 œufs")
        assertTrue(listOf(good, poor, unrated, dessert).all(CatalogPolicy::accepts))
        // La note sert au classement, pas au tri à l'entrée.
        assertTrue(CatalogPolicy.wellRated(good))
        assertFalse(CatalogPolicy.wellRated(poor) || CatalogPolicy.wellRated(unrated))
        // Une fiche vide n'entre jamais.
        assertFalse(CatalogPolicy.accepts(entry("Astuce", null, null, "1 citron")))
        assertNotNull(RecipeJsonLdExtractor.extractDetails(page("X", null, null, "a", "b")))
    }

    private val pages = mapOf(
        "https://x.fr/sitemap.xml" to "<sitemapindex><sitemap><loc>https://x.fr/sitemap-recettes-1.xml</loc></sitemap><sitemap><loc>https://x.fr/sitemap-images-1.xml</loc></sitemap></sitemapindex>",
        "https://x.fr/sitemap-recettes-1.xml" to """<urlset><url><loc>https://www.cuisineaz.com/recettes/curry-1.aspx</loc><lastmod>2026-11-01</lastmod></url>
            <url><loc>https://www.cuisineaz.com/categories/plats-c1</loc></url><url><loc>https://www.cuisineaz.com/recettes/astuce-2.aspx</loc></url>
            <url><loc>https://www.cuisineaz.com/recettes/curry-1.aspx</loc></url></urlset>""",
        "https://www.cuisineaz.com/recettes/curry-1.aspx" to page("Curry de pois chiches", "Plat", "4.5", "400 g de pois chiches", "200 g de riz"),
        "https://www.cuisineaz.com/recettes/astuce-2.aspx" to "<html>pas de recette ici</html>",
    )

    @Test fun collecte_d_un_site_par_son_sitemap() = kotlinx.coroutines.test.runTest {
        val fetched = mutableListOf<String>()
        val crawler = fr.mescourses.core.catalog.SiteCrawler { url -> fetched += url; pages[url] ?: error("404") }
        val source = fr.mescourses.core.catalog.CatalogSource("cuisineaz", listOf("https://x.fr/sitemap.xml"), Regex("recette"), Regex("""cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"""))
        val list = crawler.pages(source)
        // Le sitemap d'images n'est pas lu ; catégories et doublons écartés.
        assertEquals(listOf("https://www.cuisineaz.com/recettes/curry-1.aspx", "https://www.cuisineaz.com/recettes/astuce-2.aspx"), list.map { it.url })
        assertFalse("https://x.fr/sitemap-images-1.xml" in fetched)
        val entry = crawler.read(source, list[0])
        assertEquals("Curry de pois chiches", entry?.title)
        assertEquals("2026-11-01", entry?.lastmod)
        assertNull(crawler.read(source, list[1]))
        assertTrue(fr.mescourses.core.catalog.CatalogSources.all.map { it.siteId }.containsAll(listOf("cuisineaz", "ptitchef", "marmiton", "jow")))
    }

    @Test fun site_sans_sitemap_lu_par_ses_rubriques_paginees() = kotlinx.coroutines.test.runTest {
        fun list(vararg ids: Int) = ids.joinToString("") { """<a class="card" href="/tarte-$it-r$it.htm">x</a><a href="/recettes-plats/pates/">rubrique</a>""" }
        val site = mapOf(
            "https://www.750g.com/recettes-plats/pates/" to list(1, 2),
            "https://www.750g.com/recettes-plats/pates/?page=2" to list(3) + """<a href="https://www.750g.com/tarte-1-r1.htm?utm=x">encore</a>""",
            // Après la dernière page, le site ressert des recettes déjà vues : on s'arrête.
            "https://www.750g.com/recettes-plats/pates/?page=3" to list(1, 2),
            // Sous-rubrique : les mêmes recettes, plus une.
            "https://www.750g.com/recettes-plats/pates/lasagnes/" to list(2, 4),
        )
        val fetched = mutableListOf<String>()
        val crawler = fr.mescourses.core.catalog.SiteCrawler { url -> fetched += url; site[url] ?: error("404") }
        val source = fr.mescourses.core.catalog.CatalogSource(
            "750g", emptyList(), null, Regex("""750g\.com/[^/]+-r\d+\.htm$"""),
            listings = listOf("https://www.750g.com/recettes-plats/pates/", "https://www.750g.com/recettes-plats/pates/lasagnes/"),
        )
        // Chaque recette une seule fois, même présente dans deux rubriques ou sur deux pages.
        assertEquals((1..4).map { "https://www.750g.com/tarte-$it-r$it.htm" }.toSet(), crawler.pages(source).map { it.url }.toSet())
        assertEquals(4, crawler.pages(source).size)
        assertFalse("https://www.750g.com/recettes-plats/pates/?page=4" in fetched, fetched.toString())
        assertNotNull(fr.mescourses.core.catalog.CatalogSources.byId("750g"))
    }

    // ---------- Recherche locale et mise à jour ----------

    private fun local(url: String, title: String, category: String?, rating: String?, vararg ingredients: String) =
        CatalogEntry.of(RecipeJsonLdExtractor.extractDetails(page(title, category, rating, *ingredients), url)!!, "cuisineaz", url, "2026-11-01")

    private val leek = local("https://www.cuisineaz.com/recettes/a-1.aspx", "Gratin de poireaux au jambon", "Plat", "4.6", "4 poireaux", "4 tranches de jambon", "300 g de pommes de terre", "20 cl de crème")
    private val tart = local("https://www.cuisineaz.com/recettes/b-2.aspx", "Tarte aux pommes", "Dessert", "4.8", "4 pommes", "1 pâte brisée", "50 g de sucre")
    private val curry = local("https://www.cuisineaz.com/recettes/c-3.aspx", "Curry de pois chiches", "Plat", null, "400 g de pois chiches", "200 g de riz", "1 oignon")

    @Test fun recherche_dans_le_catalogue() = kotlinx.coroutines.test.runTest {
        val store = InMemoryCatalog(listOf(curry, tart, leek))
        val q = fr.mescourses.core.catalog.CatalogQuery
        // Pluriel, accents et mots vides ne comptent pas ; le mot peut venir du titre ou des ingrédients.
        assertEquals(listOf("gratin", "poireau"), q.words("Gratin de Poireaux"))
        assertEquals(listOf(leek.url), store.search(q.of("poireaux jambon", emptySet(), 11)).map { it.url })
        assertEquals(listOf(leek.url), store.search(q.of("pommes de terre", emptySet(), 11)).map { it.url })
        // Les filtres de Découvrir deviennent des critères ; les bien notées passent devant.
        val plats = q.of("", setOf(fr.mescourses.core.recipes.RecipeFilter.PLAT), 11)
        assertEquals(setOf(Course.PLAT), plats.courses)
        assertEquals(listOf(leek.url, curry.url), store.search(plats).map { it.url })
        val vege = q.of("", setOf(fr.mescourses.core.recipes.RecipeFilter.VEGETARIEN, fr.mescourses.core.recipes.RecipeFilter.MAX_20), 11)
        assertEquals(DishKind.VEGE, vege.kind)
        assertEquals(20, vege.maxMinutes)
        assertEquals(11, q.of("", setOf(fr.mescourses.core.recipes.RecipeFilter.SAISON), 11).month)
        assertEquals("Tarte aux pommes", with(fr.mescourses.core.catalog.CatalogQuery(words = listOf("pomme"), courses = setOf(Course.DESSERT))) { store.search(this) }.single().toHit().title)
    }

    @Test fun mise_a_jour_ne_lit_que_les_nouveautes() = kotlinx.coroutines.test.runTest {
        val source = fr.mescourses.core.catalog.CatalogSource("cuisineaz", listOf("https://x.fr/s.xml"), null, Regex("""cuisineaz\.com/recettes/[^/]+-\d+\.aspx$"""))
        val site = mutableMapOf(
            "https://x.fr/s.xml" to """<urlset><url><loc>${leek.url}</loc><lastmod>2026-11-01</lastmod></url>
                <url><loc>https://www.cuisineaz.com/recettes/vieux-8.aspx</loc><lastmod>2026-01-01</lastmod></url>
                <url><loc>https://www.cuisineaz.com/recettes/neuf-9.aspx</loc><lastmod>2026-11-08</lastmod></url>
                <url><loc>https://www.cuisineaz.com/recettes/vide-7.aspx</loc><lastmod>2026-11-09</lastmod></url></urlset>""",
            "https://www.cuisineaz.com/recettes/neuf-9.aspx" to page("Soupe de courge", "Soupe", "4.2", "1 courge", "2 carottes", "1 oignon"),
            "https://www.cuisineaz.com/recettes/vide-7.aspx" to "<html></html>",
        )
        val fetched = mutableListOf<String>()
        val store = InMemoryCatalog(listOf(leek))
        var clock = 10L * fr.mescourses.core.catalog.CatalogUpdater.DAY_MS
        val updater = fr.mescourses.core.catalog.CatalogUpdater(store, { url -> fetched += url; site[url] ?: error("404") }, listOf(source)) { clock }
        assertTrue(updater.due())
        val report = updater.update()
        // La fiche connue et la page plus ancienne que le catalogue ne sont pas relues.
        assertEquals(1, report.added)
        assertEquals(setOf("https://x.fr/s.xml", "https://www.cuisineaz.com/recettes/neuf-9.aspx", "https://www.cuisineaz.com/recettes/vide-7.aspx"), fetched.toSet())
        assertEquals(2, store.count())
        assertFalse(updater.due())
        // Le lendemain : rien de neuf, la page vide n'est pas relue ; une fiche modifiée l'est.
        clock += 2 * fr.mescourses.core.catalog.CatalogUpdater.DAY_MS
        fetched.clear()
        assertEquals(0, updater.update().added)
        assertEquals(listOf("https://x.fr/s.xml"), fetched)
        site["https://x.fr/s.xml"] = site.getValue("https://x.fr/s.xml").replace("2026-11-08", "2026-11-12")
        site["https://www.cuisineaz.com/recettes/neuf-9.aspx"] = page("Soupe de courge et carottes", "Soupe", "4.2", "1 courge", "2 carottes", "1 oignon")
        assertEquals(1, updater.update().added)
        assertEquals("Soupe de courge et carottes", store.byUrl("https://www.cuisineaz.com/recettes/neuf-9.aspx")?.title)
    }
}

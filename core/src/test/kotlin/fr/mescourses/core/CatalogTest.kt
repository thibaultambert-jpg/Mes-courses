package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogPolicy
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.Sitemap
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

    @Test fun gros_site_plats_bien_notes_petit_site_tout() {
        val big = CatalogPolicy.forSite(108_052)
        val small = CatalogPolicy.forSite(3_747)
        assertFalse(big.everything)
        assertTrue(small.everything)
        val goodDish = entry("Curry de pois chiches", "Plat", "4.5", "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz")
        val poorDish = entry("Curry de pois chiches", "Plat", "3.2", "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz")
        val unrated = entry("Curry de pois chiches", "Plat", null, "400 g de pois chiches", "20 cl de lait de coco", "200 g de riz")
        val dessert = entry("Mousse au chocolat", "Dessert", "4.9", "200 g de chocolat", "6 œufs")
        assertTrue(big.accepts(goodDish))
        assertFalse(big.accepts(poorDish))
        assertFalse(big.accepts(unrated))
        assertFalse(big.accepts(dessert))
        // Petit site : tout, y compris les desserts et les recettes sans note.
        assertTrue(listOf(goodDish, poorDish, unrated, dessert).all(small::accepts))
        // Une fiche vide n'entre jamais.
        assertFalse(small.accepts(entry("Astuce", null, null, "1 citron")))
        assertNotNull(RecipeJsonLdExtractor.extractDetails(page("X", null, null, "a", "b")))
    }
}

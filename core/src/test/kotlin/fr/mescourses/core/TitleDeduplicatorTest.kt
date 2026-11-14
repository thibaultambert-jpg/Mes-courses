package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogQuery
import fr.mescourses.core.catalog.CatalogRationalizer
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.RationalizationConfig
import fr.mescourses.core.catalog.TitleDeduplicator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleDeduplicatorTest {
    private fun e(n: Int, title: String, site: String = "marmiton", rating: Double? = null, count: Int? = null, ingredients: Int = 4) =
        CatalogEntry(
            url = "https://www.$site.fr/recettes/r-$n.html", siteId = site, title = title,
            ingredients = List(ingredients) { "ingrédient $it" }, rating = rating, ratingCount = count,
        )

    private fun groups(entries: List<CatalogEntry>, t: Double = 0.75, crossSites: Boolean = true) =
        TitleDeduplicator.groups(entries, t, crossSites = crossSites).map { it.toList() }

    private fun words(title: String) = CatalogQuery.words(title).toSet()

    @Test fun titres_identiques_regroupes() {
        val list = listOf(e(1, "Poulet basquaise"), e(2, "Gratin dauphinois"), e(3, "Poulet basquaise"), e(4, "POULET  basquaise !"), e(5, "Recette"), e(6, "recette"))
        // « Recette » n'a aucun mot utile : jamais regroupé.
        assertEquals(listOf(listOf(0, 2, 3)), groups(list))
    }

    @Test fun titres_proches_regroupes_titres_differents_separes() {
        val proche1 = "Tarte aux pommes caramélisées et cannelle"
        val proche2 = "Tarte aux pommes caramélisées"
        val autre = "Tarte aux poires"
        val loin = "Poulet curry coco"
        assertEquals(0.75, TitleDeduplicator.jaccard(words(proche1), words(proche2)))
        assertEquals(0.2, TitleDeduplicator.jaccard(words(proche1), words(autre)))
        assertEquals(0.0, TitleDeduplicator.jaccard(words(autre), words(loin)))
        val list = listOf(e(1, proche1), e(2, autre), e(3, loin), e(4, proche2))
        assertEquals(listOf(listOf(0, 3)), groups(list))
        assertEquals(emptyList(), groups(list, t = 0.8))
    }

    @Test fun filtre_par_prefixe_ne_rate_aucune_paire() {
        val vocab = listOf(
            "poulet", "boeuf", "porc", "saumon", "thon", "carotte", "poireau", "courgette", "tomate", "oignon",
            "gratin", "tarte", "quiche", "soupe", "veloute", "salade", "risotto", "curry", "tajine", "wok",
            "citron", "miel", "moutarde", "basilic", "persil", "ail", "creme", "fromage", "chevre", "comte",
            "riz", "lentille", "pois", "chiche", "epinard", "brocoli", "champignon", "noix", "amande", "chocolat",
        )
        for (seed in 1..3) {
            val rnd = Random(seed)
            val list = List(500) { i ->
                e(i, List(2 + rnd.nextInt(5)) { vocab[rnd.nextInt(vocab.size)] }.joinToString(" "), site = if (rnd.nextBoolean()) "a" else "b")
            }
            for (t in listOf(0.5, 0.6, 0.75, 1.0)) for (cross in listOf(true, false)) {
                assertEquals(bruteForce(list, t, cross), groups(list, t, cross), "graine $seed, seuil $t, crossSites $cross")
            }
        }
    }

    private fun bruteForce(list: List<CatalogEntry>, t: Double, crossSites: Boolean): List<List<Int>> {
        val w = list.map { words(it.title) }
        val parent = IntArray(list.size) { it }
        fun find(x: Int): Int = if (parent[x] == x) x else find(parent[x]).also { parent[x] = it }
        for (i in list.indices) for (j in i + 1 until list.size) {
            if (w[i].isEmpty() || w[j].isEmpty()) continue
            if (!crossSites && list[i].siteId != list[j].siteId) continue
            if (TitleDeduplicator.jaccard(w[i], w[j]) >= t) { val a = find(i); val b = find(j); if (a != b) parent[maxOf(a, b)] = minOf(a, b) }
        }
        return list.indices.filter { w[it].isNotEmpty() }.groupBy { find(it) }.values.filter { it.size >= 2 }.sortedBy { it[0] }
    }

    @Test fun la_mieux_notee_gardee() {
        val list = listOf(
            e(1, "Lasagnes à la bolognaise", rating = 5.0, count = 2), // score ≈ 4,17
            e(2, "Hachis parmentier"),
            e(3, "Lasagnes bolognaise", rating = 4.7, count = 500), // score ≈ 4,69 : gardée
            e(4, "Lasagnes à la bolognaise", rating = 4.7, count = 400),
        )
        assertEquals(listOf("r-2", "r-3"), TitleDeduplicator.dedupe(list).map { it.url.substringAfterLast('/').removeSuffix(".html") })
        // À égalité de note et d'avis : le plus d'ingrédients, puis l'url la plus petite.
        val egal = listOf(e(9, "Pot-au-feu", ingredients = 5), e(8, "Pot-au-feu", ingredients = 6), e(7, "Pot-au-feu", ingredients = 6))
        assertEquals(listOf(egal[2]), TitleDeduplicator.dedupe(egal))
    }

    @Test fun pas_de_retrait_par_simple_chainage() {
        // A ~ B (3/4) et B ~ C (3/4), mais A et C n'ont que 2 mots sur 4 en commun : un seul groupe…
        val a = e(1, "Tarte pomme cannelle", rating = 4.9, count = 300)
        val b = e(2, "Tarte pomme cannelle miel", rating = 4.5, count = 100)
        val c = e(3, "Pomme cannelle miel", rating = 4.2, count = 50)
        assertEquals(listOf(listOf(0, 1, 2)), groups(listOf(a, b, c)))
        // … mais C ne ressemble pas directement à A, la seule meilleure fiche gardée : C reste.
        assertEquals(listOf(a, c), TitleDeduplicator.dedupe(listOf(a, b, c)))
        // Si B est la meilleure, elle couvre A et C.
        val b2 = b.copy(rating = 5.0, ratingCount = 1000)
        assertEquals(listOf(b2), TitleDeduplicator.dedupe(listOf(a, b2, c)))
    }

    @Test fun meme_site_seulement_si_cross_sites_faux() {
        val list = listOf(e(1, "Chili con carne", site = "a"), e(2, "Chili con carne", site = "b"), e(3, "Chili con carne", site = "a"))
        assertEquals(listOf(listOf(0, 1, 2)), groups(list))
        assertEquals(listOf(listOf(0, 2)), groups(list, crossSites = false))
        assertEquals(2, TitleDeduplicator.dedupe(list, crossSites = false).size)
    }

    @Test fun resultat_deterministe() {
        val rnd = Random(42)
        val vocab = List(30) { "mot${'a' + it}x" }
        val list = List(300) { e(it, List(2 + rnd.nextInt(3)) { vocab[rnd.nextInt(vocab.size)] }.joinToString(" "), rating = 4.0 + rnd.nextInt(10) / 10.0, count = 10 + rnd.nextInt(50)) }
        val a = groups(list)
        assertTrue(a.isNotEmpty())
        assertEquals(a, groups(list))
        assertEquals(TitleDeduplicator.dedupe(list), TitleDeduplicator.dedupe(list))
        // Même ensemble de fiches gardées quel que soit l'ordre d'entrée.
        assertEquals(TitleDeduplicator.dedupe(list).map { it.url }.toSet(), TitleDeduplicator.dedupe(list.reversed()).map { it.url }.toSet())
    }

    @Test fun process_deduplique_par_defaut() {
        fun r(n: Int, title: String, site: String) = e(n, title, site, rating = 4.8, count = 20 + n).copy(totalMinutes = 30, imageUrl = "https://img/$n.jpg", course = Course.PLAT)
        val list = listOf(r(1, "Blanquette de veau", "marmiton"), r(2, "Blanquette de veau", "750g"), r(3, "Boeuf bourguignon", "marmiton"))
        val res = CatalogRationalizer.process(list)
        assertEquals(listOf("Blanquette de veau", "Boeuf bourguignon"), res.kept.map { it.title })
        assertEquals("750g", res.kept.first().siteId) // plus d'avis
        assertEquals(1, res.report.sites["marmiton"]!!.duplicates)
        assertEquals(3, CatalogRationalizer.process(list, RationalizationConfig(dedupeTitles = false)).kept.size)
    }

    @Test fun deux_cent_mille_titres_synthetiques_sous_deux_minutes() {
        if (System.getProperty("tests.lents") != "true") return
        val rnd = Random(7)
        // Vocabulaire de 6 000 mots à distribution de Zipf ; 10 % de quasi-doublons d'un titre précédent.
        val vocab = List(6_000) { i -> "m" + i.toString(36) + "q" }
        val cumul = DoubleArray(vocab.size).also { c -> var s = 0.0; for (i in c.indices) { s += 1.0 / (i + 1); c[i] = s } }
        fun word(): String { val x = rnd.nextDouble() * cumul.last(); var i = cumul.binarySearch(x); if (i < 0) i = -i - 1; return vocab[minOf(i, vocab.size - 1)] }
        val titles = ArrayList<String>(200_000)
        repeat(200_000) {
            titles += if (titles.size > 10 && rnd.nextInt(10) == 0) titles[rnd.nextInt(titles.size)] + " " + word()
            else List(2 + rnd.nextInt(6)) { word() }.joinToString(" ")
        }
        val list = titles.mapIndexed { i, t -> e(i, t, site = "s${i % 11}") }
        val start = System.nanoTime()
        val g = TitleDeduplicator.groups(list)
        val seconds = (System.nanoTime() - start) / 1e9
        println("200 000 titres synthétiques : ${"%.1f".format(seconds)} s, ${g.size} groupes, ${g.sumOf { it.size - 1 }} doublons")
        assertTrue(g.isNotEmpty())
        assertTrue(seconds < 120, "trop lent : $seconds s")
    }
}

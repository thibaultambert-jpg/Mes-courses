package fr.mescourses.core

import fr.mescourses.core.ingredients.PairingGraph
import fr.mescourses.core.ingredients.PairingGraph.Pairing
import fr.mescourses.core.menu.IngredientOverlap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/** Graphe des accords entre ingrédients. */
class PairingGraphTest {

    private val graph = PairingGraph(
        listOf(
            Pairing("tomate", "basilic", 0.9),
            Pairing("tomate", "mozzarella", 0.7),
            Pairing("basilic", "mozzarella", 0.5),
            Pairing("tomate", "oignon", 0.3),
        ),
    )

    @Test fun score_moyen_des_paires_presentes() {
        assertEquals((0.9 + 0.7 + 0.5) / 3, graph.affinityScore(listOf("tomate", "basilic", "mozzarella", "sel")), 1e-9)
        assertEquals(0.0, graph.affinityScore(listOf("tomate")))
        assertEquals(0.0, graph.affinityScore(listOf("sel", "poivre")))
    }

    @Test fun symetrie() {
        assertEquals(graph.affinityScore(listOf("tomate", "basilic")), graph.affinityScore(listOf("basilic", "tomate")))
        assertEquals(0.9, graph.affinityScore(listOf("basilic", "tomate")))
        assertTrue(graph.bestPairings("basilic").any { it.first == "tomate" })
    }

    @Test fun meilleurs_accords_tries() {
        assertEquals(listOf("basilic" to 0.9, "mozzarella" to 0.7, "oignon" to 0.3), graph.bestPairings("tomate"))
        assertEquals(listOf("basilic" to 0.9), graph.bestPairings("tomate", n = 1))
        assertTrue(graph.bestPairings("inconnu").isEmpty())
    }

    @Test fun graphe_charge_depuis_la_ressource() {
        assertSame(PairingGraph.load(), PairingGraph.load())
        assertTrue(PairingGraph.load().affinityScore(listOf("tomate", "basilic")) > 0.0)
        assertTrue(PairingGraph.load().bestPairings("tomate").isNotEmpty())
        assertTrue(PairingGraph.load().bestPairings("inconnu").isEmpty())
    }

    private fun pairesDeLaRessource(): List<Pairing> {
        val texte = PairingGraph::class.java.getResourceAsStream("/fr/mescourses/core/ingredients/pairings.json")!!
            .bufferedReader().readText()
        return Json.parseToJsonElement(texte).jsonObject["pairs"]!!.jsonArray.map {
            val o: JsonObject = it.jsonObject
            Pairing(o["a"]!!.jsonPrimitive.content, o["b"]!!.jsonPrimitive.content, o["w"]!!.jsonPrimitive.content.toDouble())
        }
    }

    @Test fun pairings_json_est_coherent() {
        val paires = pairesDeLaRessource()
        assertTrue(paires.size >= 50, "au moins 50 paires, trouvé ${paires.size}")
        paires.forEach { p ->
            assertEquals(p.a, IngredientOverlap.key(p.a), "clé non normalisée : ${p.a}")
            assertEquals(p.b, IngredientOverlap.key(p.b), "clé non normalisée : ${p.b}")
            assertNotEquals(p.a, p.b, "paire réflexive : ${p.a}")
            assertTrue(p.w > 0.0 && p.w <= 1.0, "poids hors (0,1] : $p")
        }
        val vues = paires.map { setOf(it.a, it.b) }
        assertEquals(vues.size, vues.toSet().size, "paire en doublon")
    }

    @Test fun meilleurs_accords_du_chou_rouge() {
        val top = PairingGraph.load().bestPairings("chou rouge", n = 2).map { it.first }
        assertEquals(listOf("pomme", "lardon"), top)
    }

    @Test fun score_d_un_menu_harmonieux_superieur_a_un_menu_sans_accord() {
        val g = PairingGraph.load()
        val harmonieux = g.affinityScore(listOf("tomate", "basilic", "mozzarella", "saumon", "aneth"))
        val sansAccord = g.affinityScore(listOf("tomate", "chocolat", "sardine", "kiwi"))
        assertTrue(harmonieux > sansAccord, "$harmonieux <= $sansAccord")
    }
}

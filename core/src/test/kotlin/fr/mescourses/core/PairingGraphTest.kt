package fr.mescourses.core

import fr.mescourses.core.ingredients.PairingGraph
import fr.mescourses.core.ingredients.PairingGraph.Pairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test fun graphe_vide_charge_depuis_la_ressource() {
        assertSame(PairingGraph.EMPTY, PairingGraph.load())
        assertEquals(0.0, PairingGraph.load().affinityScore(listOf("tomate", "basilic")))
        assertTrue(PairingGraph.load().bestPairings("tomate").isEmpty())
    }
}

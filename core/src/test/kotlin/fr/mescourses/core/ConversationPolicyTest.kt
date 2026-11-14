package fr.mescourses.core

import fr.mescourses.core.llm.CarryOver
import fr.mescourses.core.llm.ContextBudget
import fr.mescourses.core.llm.ConversationPolicy
import fr.mescourses.core.llm.Msg
import fr.mescourses.core.llm.MsgRole
import fr.mescourses.core.llm.Rollover
import fr.mescourses.core.llm.TokenEstimate
import fr.mescourses.core.llm.Transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationPolicyTest {

    private val report = listOf(Msg(MsgRole.USER, "état"), Msg(MsgRole.ASSISTANT, CarryOver.ACK))
    private val policy = ConversationPolicy(ContextBudget(4_096, 1_400))

    private fun message(tokens: Int) = "a".repeat((tokens * TokenEstimate.CHARS_PER_TOKEN_TEXT).toInt())

    @Test
    fun estimation_tokens_d_un_prompt_menu_dans_une_fourchette() {
        val phrase = "Tu composes le menu de la semaine d'une famille avec des légumes de saison et des plats équilibrés. "
        val texte = phrase.repeat(45).take(4_500)
        val tokens = TokenEstimate.text(texte)
        assertTrue(tokens in 1_100..1_700, "tokens = $tokens")
        assertEquals(0, TokenEstimate.text(""))
        assertEquals(10, TokenEstimate.text("a".repeat(33)))
        assertEquals(10 + 8, TokenEstimate.tools(listOf("a".repeat(28))))
    }

    @Test
    fun bascule_avant_le_tour_qui_deborde() {
        val r = policy.decide(2_500, Transcript(), 800, message(100), 600) { report }
        val recreate = assertIs<Rollover.Recreate>(r)
        assertEquals(report, recreate.initial)
    }

    @Test
    fun garde_la_conversation_quand_il_reste_de_la_place() {
        val r = policy.decide(1_000, Transcript(), 800, message(100), 600) { report }
        assertEquals(Rollover.Keep, r)
    }

    @Test
    fun bascule_apres_n_tours_a_outils() {
        val tours = List(3) { Transcript.Turn("u", "a", toolTokens = 10) } + Transcript.Turn("u", "a")
        val p = ConversationPolicy(ContextBudget(8_192, 1_000), maxToolTurns = 3)
        assertIs<Rollover.Recreate>(p.decide(500, Transcript(tours), 100, "ok", 0) { report })
        val moins = ConversationPolicy(ContextBudget(8_192, 1_000), maxToolTurns = 4)
        assertEquals(Rollover.Keep, moins.decide(500, Transcript(tours), 100, "ok", 0) { report })
    }

    @Test
    fun sans_token_count_on_estime_depuis_la_transcription() {
        val grosTour = Transcript.Turn(message(500), message(500), toolTokens = 400)
        val transcript = Transcript().plus(grosTour).plus(grosTour)
        assertEquals(500 + 2 * (1_000 + 400), transcript.estimatedTokens(500))
        assertIs<Rollover.Recreate>(policy.decide(null, transcript, 500, "ok", 0) { report })
        assertEquals(Rollover.Keep, policy.decide(null, Transcript(), 500, "ok", 0) { report })
    }

    @Test
    fun report_courses_borne_et_alterne_les_roles() {
        val produits = List(20) { "Produit numéro $it de la gamme" }
        val msgs = CarryOver.courses("2 articles, 7,40 €", produits, "x".repeat(2_000))
        assertEquals(listOf(MsgRole.USER, MsgRole.ASSISTANT), msgs.map { it.role })
        val user = msgs[0].text
        assertTrue(user.length <= 900, "longueur = ${user.length}")
        assertTrue(user.startsWith("[Reprise de la conversation précédente]"))
        assertTrue("Panier : 2 articles, 7,40 €" in user)
        assertTrue("Produit numéro 12 " !in user)
        assertEquals(CarryOver.ACK, msgs[1].text)
    }

    @Test
    fun report_vide_sans_etat() {
        assertTrue(CarryOver.courses(null, emptyList(), null).isEmpty())
        assertTrue(CarryOver.menu("  ", "").isEmpty())
        assertTrue(CarryOver.chat(null).isEmpty())
    }

    @Test
    fun report_chat_reprend_la_derniere_reponse() {
        val msgs = CarryOver.chat("Voici ma réponse.")
        assertEquals(2, msgs.size)
        assertTrue("Dernière réponse : « Voici ma réponse. »" in msgs[0].text)
        assertTrue(CarryOver.chat("y".repeat(2_000))[0].text.length <= 600)
    }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "attendu ${T::class.simpleName}, obtenu $value")
        return value
    }
}

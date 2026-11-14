package fr.mescourses.core

import fr.mescourses.core.ingredients.IngredientMapper
import fr.mescourses.core.ingredients.IngredientMapper.LineMapping
import fr.mescourses.core.ingredients.IngredientMapper.Status
import fr.mescourses.core.shopping.Aisle
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class IngredientMapperTest {

    private fun resource(path: String): String? =
        IngredientMapperTest::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun testMapping() = IngredientMapper.load(resource("/mapping-test.json") ?: fail("mapping-test.json absent"))

    private fun useTestMapping() = IngredientMapper.override(testMapping())

    @AfterTest fun reset() = IngredientMapper.override(null)

    @Test fun regroupement_transitif_resolu() {
        useTestMapping()
        assertEquals("oignon", IngredientMapper.mapKey("oignon jaune"))
        assertEquals("oignon", IngredientMapper.mapKey("oignon doux"))
        assertEquals("oignon", IngredientMapper.mapKey("oignon"))
        // Variante non normalisée (accents, pluriel) côté appel comme côté fichier.
        assertEquals("echalote", IngredientMapper.mapKey("échalotes grises"))
        // Inconnue : la clé elle-même.
        assertEquals("carotte", IngredientMapper.mapKey("carotte"))
        // `from` répété : le dernier gagne, et c'est signalé.
        assertEquals("creme allegee", IngredientMapper.mapKey("creme legere"))
        assertTrue(testMapping().warnings.any { "creme legere" in it })
    }

    @Test fun cycle_de_regroupement_refuse() {
        val json = """{"merges":[{"from":"a b","to":"c d"},{"from":"c d","to":"e f"},{"from":"e f","to":"a b"}]}"""
        val e = assertFailsWith<IllegalStateException> { IngredientMapper.load(json) }
        assertTrue("a b → c d → e f → a b" in e.message.orEmpty(), e.message)
        // Chaîne trop longue (> 50 sauts) : refusée aussi.
        val long = (0..60).joinToString(",") { """{"from":"k$it","to":"k${it + 1}"}""" }
        assertFailsWith<IllegalStateException> { IngredientMapper.load("""{"merges":[$long]}""") }
    }

    @Test fun ligne_reconnue_malgre_accents_et_pluriel() {
        useTestMapping()
        assertEquals(LineMapping("echalote", null), IngredientMapper.mapLine("3 Echalote grise ciselee"))
        assertEquals(LineMapping("echalote", null), IngredientMapper.mapLine("échalotes grises ciselées"))
        assertEquals(LineMapping(null, Status.IGNORE), IngredientMapper.mapLine("200 g"))
        assertEquals(LineMapping(null, Status.IGNORE), IngredientMapper.mapLine(" 2 "))
        assertEquals(LineMapping(null, Status.IGNORE), IngredientMapper.mapLine("L"))
        assertNull(IngredientMapper.mapLine("200 g de farine"))
    }

    @Test fun rayon_par_nom_d_enum_ou_label() {
        useTestMapping()
        assertEquals(Aisle.FRUITS_LEGUMES, IngredientMapper.aisleOverride("oignon jaune"))
        assertEquals(Aisle.FRUITS_LEGUMES, IngredientMapper.aisleOverride("echalote"))
        assertEquals(Aisle.CREMERIE, IngredientMapper.aisleOverride("crème légère"))
        assertNull(IngredientMapper.aisleOverride("sel et poivre"))
        assertNull(IngredientMapper.aisleOverride("carotte"))
        Aisle.entries.forEach {
            assertEquals(it, IngredientMapper.parseAisle(it.name))
            assertEquals(it, IngredientMapper.parseAisle(it.label))
        }
        val m = IngredientMapper.load("""{"keys":[{"key":"x","status":"ok","rayon":"Rayon imaginaire"}]}""")
        assertNull(m.keys["x"]?.aisle)
        assertTrue(m.warnings.any { "Rayon imaginaire" in it })
    }

    @Test fun statut_ignore_et_split() {
        useTestMapping()
        assertEquals(Status.OK, IngredientMapper.status("oignon jaune"))
        assertEquals(Status.SPLIT, IngredientMapper.status("sel et poivre du moulin"))
        assertEquals(Status.IGNORE, IngredientMapper.status("eau"))
        assertNull(IngredientMapper.status("mystere"))
        assertNull(IngredientMapper.status("carotte"))
    }

    @Test fun recherche_drive_forcee() {
        useTestMapping()
        assertEquals("crème légère 15%", IngredientMapper.searchQueryOverride("creme legere"))
        assertEquals("champignons de Paris", IngredientMapper.searchQueryOverride("champignons de paris"))
        assertNull(IngredientMapper.searchQueryOverride("oignon"))
    }

    /** Attendus produits par `variantOf` de tools/catalog/viewer.html exécuté sous Node. */
    @Test fun line_text_identique_au_viewer() {
        val fixtures = listOf(
            "200 g de farine" to "farine",
            "4 œufs" to "œufs",
            "50 cl de lait" to "lait",
            "1 pincée de sel" to "sel",
            "2 gousses d'ail" to "ail",
            "1 c. à soupe d’huile d'olive" to "huile d'olive",
            // `\b` JS est ASCII : « café » suivi d'un espace n'est pas une fin de mot, l'unité reste.
            "1 c. à café de sel" to "c. à café de sel",
            "3 cuillères à soupe de sucre" to "sucre",
            "1/2 citron" to "citron",
            "½ botte de Persil" to "persil",
            "200 g" to "200 g",
            "2" to "2",
            "  Tomates  " to "tomates",
            "1 boîte de tomates concassées" to "tomates concassées",
            "1,5 kg de pommes de terre" to "pommes de terre",
            "3 feuilles de laurier" to "laurier",
            "4 pièces de poulet" to "poulet",
            "1 l" to "1 l",
            "1 kg de Farine" to "farine",
            "À volonté sel" to "volonté sel",
            "1 CS de miel" to "cs de miel",
            "2 Verres d'eau" to "eau",
            "3 - 4 oignons" to "oignons",
            "100gr de beurre" to "beurre",
            "2 PINCÉES de poivre" to "poivre",
            "1 c.à.c de cumin" to "c.à.c de cumin",
        )
        fixtures.forEach { (raw, text) -> assertEquals(text, IngredientMapper.lineText(raw), "lineText(« $raw »)") }
    }

    @Test fun fichier_vide_ou_absent_donne_un_mapping_vide() {
        for (text in listOf(null, "", "   ", "{}", "pas du json")) {
            val m = IngredientMapper.load(text)
            assertTrue(m.merges.isEmpty() && m.keys.isEmpty() && m.lines.isEmpty(), "load($text)")
            IngredientMapper.override(m)
            assertEquals("oignon jaune", IngredientMapper.mapKey("oignon jaune"))
            assertNull(IngredientMapper.status("oignon"))
            assertNull(IngredientMapper.mapLine("200 g"))
            assertNull(IngredientMapper.aisleOverride("oignon"))
            assertNull(IngredientMapper.searchQueryOverride("oignon"))
        }
    }

    @Test fun mapping_ingredients_json_est_coherent() {
        val text = resource("/fr/mescourses/core/ingredients/mapping-ingredients.json") ?: fail("ressource réelle absente")
        // Chargement complet : échoue sur cycle ou chaîne trop longue.
        val m = IngredientMapper.load(text)
        assertTrue(m.merges.isNotEmpty() && m.keys.isNotEmpty(), "mapping réel vide ?")
        val root = Json.parseToJsonElement(text).jsonObject
        val errors = mutableListOf<String>()
        val warnings = m.warnings.toMutableList()
        val statuses = setOf("ok", "split", "ignore")
        fun str(o: JsonObject, f: String): String? = (o[f] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        fun shape(what: String, k: String?) {
            if (k != null && k != ShoppingListMerger.key(k)) warnings += "$what « $k » non normalisée (≠ « ${ShoppingListMerger.key(k)} »)"
        }

        root["merges"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { mg ->
            val from = str(mg, "from")
            val to = str(mg, "to")
            if (from == null || to == null) errors += "merge incomplet : $mg"
            shape("merge.from", from)
            shape("merge.to", to)
            // Cible résolue : la clé finale n'est plus elle-même source d'un merge.
            if (from != null) {
                val final = m.merges[ShoppingListMerger.key(from)]
                if (final != null && final in m.merges) errors += "cible non résolue : $from → $final"
            }
        }
        root["keys"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { k ->
            val key = str(k, "key")
            if (key == null) { errors += "clé sans nom : $k"; return@forEach }
            shape("clé", key)
            str(k, "status")?.let { if (it !in statuses) errors += "clé « $key » : statut invalide « $it »" }
            str(k, "rayon")?.let { if (IngredientMapper.parseAisle(it) == null) errors += "clé « $key » : rayon inconnu « $it »" }
            if (ShoppingListMerger.key(key) in m.merges) warnings += "clé « $key » est source d'un merge (surcharges portées par la cible)"
        }
        root["lines"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { l ->
            val t = str(l, "text")
            if (t == null) { errors += "ligne sans texte : $l"; return@forEach }
            str(l, "status")?.let { if (it !in statuses) errors += "ligne « $t » : statut invalide « $it »" }
            shape("ligne.key", str(l, "key"))
        }
        println(
            "mapping-ingredients.json : ${m.merges.size} merges, ${m.keys.size} clés, ${m.lines.size} lignes, " +
                "${warnings.size} avertissement(s)" + warnings.joinToString("") { "\n  - $it" },
        )
        assertTrue(errors.isEmpty(), "mapping-ingredients.json incohérent :\n" + errors.joinToString("\n"))
    }
}

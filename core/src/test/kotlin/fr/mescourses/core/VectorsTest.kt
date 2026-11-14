package fr.mescourses.core

import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.EmbeddingText
import fr.mescourses.core.catalog.Int8VectorIndex
import fr.mescourses.core.catalog.Int8Vectors
import fr.mescourses.core.catalog.ReciprocalRankFusion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vecteurs INT8 : quantification identique à la CI (`tools/catalog/embed.py`), produit scalaire, index en mémoire,
 * fusion des classements.
 */
class VectorsTest {

    /**
     * Fixtures de référence : celle du module (`core/src/test/resources/embedder-golden.json`, vecteur
     * `float[i] = sin((i + 1) / 7.0)` normalisé L2) et, quand elle existe, celle écrite par `embed.py --dry-run`
     * (`tools/catalog/embedder-golden.json`). Toutes doivent passer.
     */
    private fun goldens(): List<JsonObject> {
        val own = javaClass.getResourceAsStream("/embedder-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val shared = listOf(File("../tools/catalog/embedder-golden.json"), File("tools/catalog/embedder-golden.json"))
            .firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
        return listOfNotNull(own, shared).map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun randomVector(r: Random) = ByteArray(Int8Vectors.DIM) { (r.nextInt(255) - 127).toByte() }

    @Test fun quantification_identique_au_golden() {
        for (g in goldens()) {
            assertEquals(Int8Vectors.DIM, g["dim"]!!.jsonPrimitive.int)
            assertEquals(Int8Vectors.SCALE, g["scale"]!!.jsonPrimitive.float)
            val v = g["float"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val expected = g["int8_base64"]!!.jsonPrimitive.content
            assertEquals(344, expected.length)
            val q = Int8Vectors.quantize(v)
            assertEquals(expected, Int8Vectors.encode(q))
            // Un vecteur non normalisé est normalisé avant quantification.
            assertEquals(expected, Int8Vectors.encode(Int8Vectors.quantize(FloatArray(v.size) { v[it] * 3f })))
        }
        // La formule de la fixture du module, recalculée ici.
        val sin = DoubleArray(Int8Vectors.DIM) { kotlin.math.sin((it + 1) / 7.0) }
        val norm = kotlin.math.sqrt(sin.sumOf { it * it })
        val own = goldens().first()["float"]!!.jsonArray.map { it.jsonPrimitive.float }
        own.forEachIndexed { i, x -> assertEquals((sin[i] / norm).toFloat(), x) }
    }

    @Test fun produit_scalaire_symetrique_et_borne() {
        val r = Random(1)
        repeat(200) {
            val a = randomVector(r)
            val b = randomVector(r)
            val ab = Int8Vectors.dot(a, 0, b)
            assertEquals(ab, Int8Vectors.dot(b, 0, a))
            assertEquals(a.indices.sumOf { a[it] * b[it] }, ab)
            assertTrue(kotlin.math.abs(ab) <= 127 * 127 * Int8Vectors.DIM)
        }
        // Bornes atteintes sans débordement.
        val max = ByteArray(Int8Vectors.DIM) { 127 }
        val min = ByteArray(Int8Vectors.DIM) { -127 }
        assertEquals(127 * 127 * Int8Vectors.DIM, Int8Vectors.dot(max, 0, max))
        assertEquals(-127 * 127 * Int8Vectors.DIM, Int8Vectors.dot(max, 0, min))
        // Décalage dans un tableau contigu.
        val a = randomVector(r)
        val b = randomVector(r)
        assertEquals(Int8Vectors.dot(b, 0, a), Int8Vectors.dot(a + b, Int8Vectors.DIM, a))
        // Vecteur normalisé : dot(v, v) ≈ (127 / 0,25)² = 258 064 (à l'erreur de quantification près).
        val g = goldens().first()
        val v = Int8Vectors.decode(g["int8_base64"]!!.jsonPrimitive.content)!!
        val self = Int8Vectors.dot(v, 0, v)
        val ideal = (127 / Int8Vectors.SCALE).let { it * it }
        assertTrue(kotlin.math.abs(self - ideal) / ideal < 0.01, "dot(v, v) = $self, attendu ≈ $ideal")
    }

    @Test fun encode_decode_aller_retour() {
        val r = Random(2)
        repeat(50) {
            val v = randomVector(r)
            val s = Int8Vectors.encode(v)
            assertEquals(344, s.length)
            assertContentEquals(v, Int8Vectors.decode(s))
        }
    }

    @Test fun decode_refuse_une_mauvaise_taille() {
        assertNull(Int8Vectors.decode(Int8Vectors.encode(ByteArray(255))))
        assertNull(Int8Vectors.decode(Int8Vectors.encode(ByteArray(257))))
        assertNull(Int8Vectors.decode(""))
        assertNull(Int8Vectors.decode("pas du base64 !"))
    }

    @Test fun top_k_egal_a_la_force_brute() {
        val r = Random(3)
        val ids = (1..10_000).map { it * 7L + r.nextLong(0, 7) }.shuffled(r)
        // Des doublons de vecteurs pour avoir des scores égaux : départage par id croissant.
        val vectors = HashMap<Long, ByteArray>()
        ids.forEachIndexed { i, id -> vectors[id] = if (i >= 500 && i % 10 == 0) vectors.getValue(ids[i - 500]) else randomVector(r) }
        val index = Int8VectorIndex.build(ids.map { it to vectors.getValue(it) })
        assertEquals(10_000, index.size)
        repeat(5) {
            val q = randomVector(r)
            val expected = ids.map { Int8VectorIndex.Hit(it, Int8Vectors.dot(vectors.getValue(it), 0, q)) }
                .sortedWith(compareByDescending<Int8VectorIndex.Hit> { it.score }.thenBy { it.id }).take(50)
            assertEquals(expected, index.topK(q, 50))
        }
        // Requête égale à un vecteur présent 20 fois (ids[0], ids[500], ids[1000]…) : ex aequo, plus petits ids d'abord.
        val twins = ids.filter { vectors.getValue(it) === vectors.getValue(ids[0]) }
        assertEquals(20, twins.size)
        val top = index.topK(vectors.getValue(ids[0]), 3)
        assertEquals(twins.sorted().take(3), top.map { it.id })
        assertEquals(1, top.map { it.score }.distinct().size)
        assertEquals(emptyList(), index.topK(randomVector(r), 0))
    }

    @Test fun masque_de_filtres_par_fusion_d_ids() {
        val r = Random(4)
        val index = Int8VectorIndex.build(listOf(50L, 10L, 30L, 20L, 40L).map { it to randomVector(r) })
        assertContentEquals(longArrayOf(10, 20, 30, 40, 50), index.ids)
        assertContentEquals(booleanArrayOf(true, false, true, false, true), index.mask(longArrayOf(5, 10, 30, 35, 50, 60)))
        assertContentEquals(BooleanArray(5), index.mask(longArrayOf()))
        assertContentEquals(BooleanArray(5) { true }, index.mask(longArrayOf(10, 20, 30, 40, 50)))
    }

    @Test fun top_k_respecte_le_masque() {
        val r = Random(5)
        val rows = (1L..2_000L).map { it to randomVector(r) }
        val index = Int8VectorIndex.build(rows)
        val allowed = rows.map { it.first }.filter { it % 3 == 0L }
        val mask = index.mask(allowed.toLongArray())
        val q = randomVector(r)
        val hits = index.topK(q, 20, mask)
        assertEquals(20, hits.size)
        assertTrue(hits.all { it.id % 3 == 0L })
        val expected = rows.filter { it.first % 3 == 0L }.map { Int8VectorIndex.Hit(it.first, Int8Vectors.dot(it.second, 0, q)) }
            .sortedWith(compareByDescending<Int8VectorIndex.Hit> { it.score }.thenBy { it.id }).take(20)
        assertEquals(expected, hits)
        // Moins d'autorisés que k : tous, et rien d'autre.
        assertEquals(listOf(7L), index.topK(q, 10, index.mask(longArrayOf(7))).map { it.id })
    }

    @Test fun rrf_favorise_le_present_dans_les_deux_listes() {
        val mots = listOf(1L, 2L, 3L, 4L)
        val vecteurs = listOf(5L, 3L, 6L, 7L)
        val fused = ReciprocalRankFusion.fuse(listOf(mots, vecteurs))
        assertEquals(3L, fused.first())
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), fused.toSet())
        assertEquals(7, fused.size)
        // À score égal (1er de chaque liste), meilleur rang puis ordre d'apparition.
        assertEquals(listOf(3L, 1L, 5L, 2L, 6L, 4L, 7L), fused)
        assertEquals(listOf(10L, 20L), ReciprocalRankFusion.fuse(listOf(listOf(10L), listOf(20L))))
        assertEquals(emptyList(), ReciprocalRankFusion.fuse(emptyList()))
    }

    private fun timeTopK(n: Int): Double {
        val r = Random(6)
        val data = ByteArray(n * Int8Vectors.DIM).also { r.nextBytes(it) }
        val index = Int8VectorIndex(LongArray(n) { it.toLong() }, data)
        val q = randomVector(r)
        repeat(3) { index.topK(q, 150) } // chauffe du JIT
        val times = (1..5).map {
            val t = System.nanoTime()
            val hits = index.topK(randomVector(r), 150)
            val ms = (System.nanoTime() - t) / 1e6
            assertEquals(150, hits.size)
            ms
        }.sorted()
        val median = times[times.size / 2]
        println("topK sur $n vecteurs : médiane ${"%.1f".format(median)} ms (${times.joinToString { "%.1f".format(it) }})")
        return median
    }

    @Test fun index_de_80k_vecteurs_sous_200_ms() {
        val t80 = timeTopK(80_000)
        assertTrue(t80 < 200, "80 k vecteurs : $t80 ms")
        val t200 = timeTopK(200_000)
        assertTrue(t200 < 500, "200 k vecteurs : $t200 ms")
    }

    @Test fun texte_de_passage_stable() {
        for (g in goldens()) {
            val p = g["passage_example"]!!.jsonObject
            val entry = CatalogEntry(
                url = "https://exemple.fr/gratin", siteId = "exemple", title = p["title"]!!.jsonPrimitive.content,
                keys = p["keys"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(p["text"]!!.jsonPrimitive.content, EmbeddingText.passage(entry))
            val q = g["query_example"]!!.jsonObject
            assertEquals(q["text"]!!.jsonPrimitive.content, EmbeddingText.query(q["input"]!!.jsonPrimitive.content))
            assertEquals(EmbeddingText.VERSION, g["textVersion"]!!.jsonPrimitive.int)
        }
        // Changer ce texte oblige à recalculer les vecteurs du catalogue (et à augmenter EmbeddingText.VERSION).
        val e = CatalogEntry(url = "u", siteId = "s", title = "Soupe de potiron", keys = listOf("potiron", "creme"))
        assertEquals("task: search result | text: Soupe de potiron. Ingrédients : potiron, creme", EmbeddingText.passage(e))
        assertEquals("task: search query | text: velouté", EmbeddingText.query(" velouté\n"))
    }
}

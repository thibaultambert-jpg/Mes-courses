package fr.mescourses.core.ingredients

import fr.mescourses.core.shopping.Aisle
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mapping canonique des ingrédients, chargé depuis la ressource `mapping-ingredients.json` (export v2 du viewer
 * `tools/catalog/viewer.html`, validé à la main) :
 * - `merges` ramène une clé d'ingrédient à sa forme canonique (« oignon jaune » → « oignon »), de façon transitive ;
 * - `keys` donne, pour une clé canonique, son statut, son rayon imposé et le texte à chercher en magasin ;
 * - `lines` reconnaît des lignes entières (sans quantité ni unité, cf. [lineText]) : résidus à ignorer, ou ligne
 *   rattachée directement à une clé.
 *
 * Toutes les clés (côté fichier comme côté appel) sont comparées après [ShoppingListMerger.key], pour tolérer les
 * écarts d'accents, de casse et de pluriel entre le viewer (JS) et l'application.
 *
 * Statut [Status.SPLIT] : la ligne regroupe plusieurs ingrédients (« sel et poivre »). Le découpage n'est pas
 * automatisé : l'appelant doit la traiter comme **non mappée** (garder la clé telle quelle, sans surcharge).
 *
 * Ressource absente, vide ou illisible : mapping vide (identité), sans exception. Un cycle dans `merges` est en
 * revanche une erreur de données : [IllegalStateException] au premier accès.
 */
object IngredientMapper {
    /** Sort d'une clé ou d'une ligne : gardée telle quelle, à découper en plusieurs ingrédients, ou à ignorer. */
    enum class Status { OK, SPLIT, IGNORE }

    /** Correspondance d'une ligne entière : clé canonique (null si aucune) et statut (null si inconnu). */
    data class LineMapping(val key: String?, val status: Status?)

    /** Surcharges d'une clé canonique. */
    internal data class KeyInfo(val status: Status?, val aisle: Aisle?, val search: String?)

    /** Mapping chargé et indexé (toutes les clés normalisées par [ShoppingListMerger.key]). */
    internal class Mapping(
        /** Clé source → clé canonique finale (merges déjà aplatis). */
        val merges: Map<String, String>,
        val keys: Map<String, KeyInfo>,
        /** Texte de ligne normalisé → correspondance (clé déjà passée par les merges). */
        val lines: Map<String, LineMapping>,
        /** Anomalies non bloquantes relevées au chargement (doublons, rayon ou statut inconnu…). */
        val warnings: List<String>,
    ) {
        companion object {
            val EMPTY = Mapping(emptyMap(), emptyMap(), emptyMap(), emptyList())
        }
    }

    private const val RESOURCE = "/fr/mescourses/core/ingredients/mapping-ingredients.json"
    private const val MAX_HOPS = 50

    @Serializable private data class FileJson(
        val version: Int? = null,
        val merges: List<MergeJson> = emptyList(),
        val keys: List<KeyJson> = emptyList(),
        val lines: List<LineJson> = emptyList(),
    )
    @Serializable private data class MergeJson(val from: String? = null, val to: String? = null)
    @Serializable private data class KeyJson(
        val key: String? = null,
        val status: String? = null,
        val rayon: String? = null,
        val search: String? = null,
    )
    @Serializable private data class LineJson(val text: String? = null, val key: String? = null, val status: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fromResource: Mapping by lazy {
        val text = IngredientMapper::class.java.getResourceAsStream(RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        val mapping = load(text)
        mapping.warnings.forEach { System.err.println("IngredientMapper : $it") }
        mapping
    }

    @Volatile private var testOverride: Mapping? = null

    private val current: Mapping get() = testOverride ?: fromResource

    /** Remplace le mapping (tests) ; null revient à la ressource. */
    internal fun override(m: Mapping?) {
        testOverride = m
    }

    /**
     * Construit un mapping depuis le JSON du viewer. Texte null, vide ou illisible → mapping vide.
     * @throws IllegalStateException si `merges` contient un cycle (ou une chaîne de plus de 50 sauts).
     */
    internal fun load(text: String?): Mapping {
        if (text.isNullOrBlank()) return Mapping.EMPTY
        val file = try {
            json.decodeFromString(FileJson.serializer(), text)
        } catch (e: Exception) {
            System.err.println("IngredientMapper : mapping illisible, ignoré (${e.message})")
            return Mapping.EMPTY
        }
        val warnings = mutableListOf<String>()

        // Merges bruts : le dernier `from` gagne.
        val raw = LinkedHashMap<String, String>()
        for (m in file.merges) {
            val from = ShoppingListMerger.key(m.from ?: continue)
            val to = ShoppingListMerger.key(m.to ?: continue)
            if (from.isEmpty() || to.isEmpty() || from == to) continue
            raw.put(from, to)?.let { prev -> if (prev != to) warnings += "merge « $from » répété (« $prev » remplacé par « $to »)" }
        }
        val merges = raw.keys.associateWith { resolve(it, raw) }

        fun canonical(k: String): String = ShoppingListMerger.key(k).let { merges[it] ?: it }

        val keys = LinkedHashMap<String, KeyInfo>()
        for (k in file.keys) {
            val name = k.key?.let(::canonical)?.takeIf { it.isNotEmpty() } ?: continue
            val status = parseStatus(k.status, "clé « $name »", warnings)
            val aisle = k.rayon?.takeIf { it.isNotBlank() }?.let { r ->
                parseAisle(r) ?: null.also { warnings += "clé « $name » : rayon inconnu « $r »" }
            }
            val info = KeyInfo(status, aisle, k.search?.trim()?.takeIf { it.isNotEmpty() })
            if (keys.put(name, info) != null) warnings += "clé « $name » répétée (dernière entrée gardée)"
        }

        val lines = LinkedHashMap<String, LineMapping>()
        for (l in file.lines) {
            val text = l.text?.let(ShoppingListMerger::key)?.takeIf { it.isNotEmpty() } ?: continue
            val key = l.key?.let(::canonical)?.takeIf { it.isNotEmpty() }
            val status = parseStatus(l.status, "ligne « $text »", warnings)
            if (key == null && status == null) continue
            if (lines.put(text, LineMapping(key, status)) != null) warnings += "ligne « $text » répétée (dernière entrée gardée)"
        }
        return Mapping(merges, keys, lines, warnings)
    }

    /** Suit la chaîne de merges depuis [start] jusqu'à une clé qui n'est plus source. */
    private fun resolve(start: String, raw: Map<String, String>): String {
        val chain = mutableListOf(start)
        var k = start
        repeat(MAX_HOPS) {
            val next = raw[k] ?: return k
            if (next in chain) error("Cycle dans les regroupements : ${(chain + next).joinToString(" → ")}")
            chain += next
            k = next
        }
        error("Chaîne de regroupements trop longue (> $MAX_HOPS sauts) : ${chain.joinToString(" → ")}")
    }

    private fun parseStatus(s: String?, where: String, warnings: MutableList<String>): Status? {
        if (s.isNullOrBlank()) return null
        return Status.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
            ?: null.also { warnings += "$where : statut inconnu « $s »" }
    }

    /** Rayon par nom d'enum (`FRUITS_LEGUMES`), sinon par libellé (« Fruits & légumes »). */
    internal fun parseAisle(r: String): Aisle? {
        val t = r.trim()
        return Aisle.entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
            ?: Aisle.entries.firstOrNull { it.label.equals(t, ignoreCase = true) }
    }

    /** Clé canonique d'une clé d'ingrédient ; la clé elle-même si elle n'est pas regroupée. */
    fun mapKey(key: String): String {
        return current.merges[ShoppingListMerger.key(key)] ?: key
    }

    private fun info(key: String): KeyInfo? = current.keys[ShoppingListMerger.key(mapKey(key))]

    /** Statut d'une clé (après regroupement) ; null si elle n'est pas connue. */
    fun status(key: String): Status? = info(key)?.status

    /** Correspondance d'une ligne d'ingrédient telle qu'écrite (cherchée par [lineText]) ; null si inconnue. */
    fun mapLine(raw: String): LineMapping? = current.lines[ShoppingListMerger.key(lineText(raw))]

    /** Rayon imposé pour une clé (après regroupement), à la place de celui déduit du nom ; null si aucun. */
    fun aisleOverride(key: String): Aisle? = info(key)?.aisle

    /** Texte à chercher en magasin pour une clé (après regroupement), à la place de la clé ; null si aucun. */
    fun searchQueryOverride(key: String): String? = info(key)?.search

    /*
     * Portage de `QTY` / `variantOf` de tools/catalog/viewer.html (regex recopiée telle quelle), avec trois
     * ajustements pour reproduire la sémantique JS (flag `i`, sans `u`) en java.util.regex :
     * - `\b` JS est une frontière de mot ASCII ([A-Za-z0-9_]) : « café » suivi d'un espace n'en est pas une ;
     * - `\s` JS couvre aussi les espaces Unicode (insécable…) ;
     * - `i` JS replie aussi les lettres accentuées (À/à) : CASE_INSENSITIVE + UNICODE_CASE.
     */
    private const val JS_QTY =
        """^[\s\d.,\/½¼¾⅓⅔⅛\-–à]+(?:(?:kg|g|gr|mg|l|dl|cl|ml|c\.?\s?à\s?(?:soupe|café|s|c)\.?|cuill?[eè]res?(?:\s+à\s+(?:soupe|café))?|pinc[ée]es?|gousses?|tranches?|bo[iî]tes?|sachets?|brins?|feuilles?|pots?|bottes?|verres?|filets?|poign[ée]es?|paquets?|barquettes?|bouteilles?|unit[ée]s?|pi[eè]ces?)\b\.?)?\s*(?:de |d['’]|du |des )?"""
    private const val JS_SPACE = """[\s   -     　﻿]"""
    private const val JS_WORD = "[A-Za-z0-9_]"
    private const val JS_BOUNDARY = "(?:(?<=$JS_WORD)(?!$JS_WORD)|(?<!$JS_WORD)(?=$JS_WORD))"

    private val QTY: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        JS_QTY.replace("""\s""", JS_SPACE).replace("""\b""", JS_BOUNDARY),
        java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE,
    )

    /**
     * Forme d'une ligne servant de clé de recherche dans le mapping des lignes : sans quantité ni unité en tête, en
     * minuscules (« 200 g de farine » → « farine ») ; la ligne entière en minuscules si rien ne reste (« 200 g »).
     * Identique à `variantOf` du viewer.
     */
    fun lineText(raw: String): String {
        val v = QTY.matcher(raw).replaceFirst("").trim().lowercase()
        return v.ifEmpty { raw.trim().lowercase() }
    }
}

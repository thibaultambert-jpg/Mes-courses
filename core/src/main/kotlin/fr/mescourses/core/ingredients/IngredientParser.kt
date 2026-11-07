package fr.mescourses.core.ingredients

import fr.mescourses.core.model.Ingredient
import fr.mescourses.core.model.stripAccents

/**
 * Analyse des lignes d'ingrédients en français :
 * « 200 g de farine », « 2 œufs », « 1 c. à soupe d'huile d'olive », « 1/2 citron », « sel, poivre ».
 */
object IngredientParser {

    /** Unité canonique → variantes reconnues (sans accents, en minuscules). */
    private val unitAliases: List<Pair<String, List<String>>> = listOf(
        "kg" to listOf("kg", "kilo", "kilos", "kilogramme", "kilogrammes"),
        "g" to listOf("g", "gr", "grs", "gramme", "grammes"),
        "mg" to listOf("mg"),
        "l" to listOf("l", "litre", "litres"),
        "dl" to listOf("dl", "decilitre", "decilitres"),
        "cl" to listOf("cl", "centilitre", "centilitres"),
        "ml" to listOf("ml", "millilitre", "millilitres"),
        "c. à soupe" to listOf(
            "c. a soupe", "c.a.s", "c.a.s.", "cas", "cs", "c. a s.", "cuillere a soupe", "cuilleres a soupe",
            "cuil. a soupe", "c a soupe", "cuillerees a soupe", "cuilleree a soupe", "tbsp",
        ),
        "c. à café" to listOf(
            "c. a cafe", "c.a.c", "c.a.c.", "cac", "cc", "c. a c.", "cuillere a cafe", "cuilleres a cafe",
            "cuil. a cafe", "c a cafe", "cuillerees a cafe", "cuilleree a cafe", "tsp",
        ),
        "pincée" to listOf("pincee", "pincees"),
        "gousse" to listOf("gousse", "gousses"),
        "tranche" to listOf("tranche", "tranches"),
        "botte" to listOf("botte", "bottes"),
        "boîte" to listOf("boite", "boites"),
        "sachet" to listOf("sachet", "sachets"),
        "brin" to listOf("brin", "brins"),
        "feuille" to listOf("feuille", "feuilles"),
        "verre" to listOf("verre", "verres"),
        "bol" to listOf("bol", "bols"),
        "pot" to listOf("pot", "pots"),
        "filet" to listOf("filet", "filets"),
        "noix" to listOf("noix"),
        "poignée" to listOf("poignee", "poignees"),
        "tasse" to listOf("tasse", "tasses"),
        "barquette" to listOf("barquette", "barquettes"),
        "paquet" to listOf("paquet", "paquets"),
        "bouteille" to listOf("bouteille", "bouteilles"),
        "rouleau" to listOf("rouleau", "rouleaux"),
    )

    // Variantes triées de la plus longue à la plus courte pour éviter que « c » capture « c. à soupe ».
    private val sortedAliases: List<Pair<String, String>> = unitAliases
        .flatMap { (canonical, aliases) -> aliases.map { it to canonical } }
        .sortedByDescending { it.first.length }

    private val unicodeFractions = mapOf(
        '½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125,
    )

    private val wordNumbers = mapOf(
        "un" to 1.0, "une" to 1.0, "deux" to 2.0, "trois" to 3.0, "quatre" to 4.0, "cinq" to 5.0,
        "six" to 6.0, "sept" to 7.0, "huit" to 8.0, "neuf" to 9.0, "dix" to 10.0, "douze" to 12.0,
        "demi" to 0.5, "demie" to 0.5,
    )

    private val numberRegex = Regex("""^(\d+(?:[.,]\d+)?)(?:\s*/\s*(\d+))?(?:\s*(?:-|à|a)\s*(\d+(?:[.,]\d+)?))?""")

    fun parse(line: String): Ingredient {
        val raw = line.trim().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return Ingredient(name = "", raw = line)
        var rest = raw.trimStart('-', '•', '*', ' ')

        // 1. Quantité
        var quantity: Double? = null
        val first = rest.firstOrNull()
        if (first != null && first in unicodeFractions) {
            quantity = unicodeFractions.getValue(first)
            rest = rest.drop(1).trimStart()
        } else {
            numberRegex.find(rest)?.let { m ->
                val a = m.groupValues[1].replace(',', '.').toDouble()
                val den = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()
                quantity = if (den != null && den != 0.0) a / den else a
                // Plage « 2-3 » : on garde la borne haute pour ne pas en manquer.
                m.groupValues[3].takeIf { it.isNotEmpty() }?.let { quantity = it.replace(',', '.').toDouble() }
                rest = rest.substring(m.range.last + 1).trimStart()
                // « 1 ½ »
                rest.firstOrNull()?.let { c ->
                    if (c in unicodeFractions) {
                        quantity = (quantity ?: 0.0) + unicodeFractions.getValue(c)
                        rest = rest.drop(1).trimStart()
                    }
                }
            } ?: run {
                val word = rest.substringBefore(' ').lowercase().stripAccents()
                wordNumbers[word]?.let { n ->
                    quantity = n
                    rest = rest.substringAfter(' ', "").trimStart()
                }
            }
        }

        // 2. Unité (seulement si une quantité a été trouvée ou si l'unité ouvre la ligne, ex. « pincée de sel »)
        var unit: String? = null
        val restNorm = rest.lowercase().stripAccents()
        for ((alias, canonical) in sortedAliases) {
            if (restNorm.startsWith(alias)) {
                val after = restNorm.getOrNull(alias.length)
                // l'unité doit être un mot entier
                if (after == null || after == ' ' || after == '.' || after == '\'' || after == '’' || after == ')') {
                    if (quantity == null && alias.length <= 2) break // « l'huile », « g » isolé : ambigu
                    unit = canonical
                    rest = rest.substring(alias.length).trimStart('.', ' ')
                    break
                }
            }
        }
        if (unit == null && quantity == null) {
            // « une pincée de sel » déjà géré par wordNumbers ; « sel » seul : pas de quantité.
        }

        // 3. Nom : retire « de », « d' », « des » en tête et les précisions entre parenthèses
        var name = rest
            .replace(Regex("""^(?i)(de la |de l'|de l’|du |des |de |d'|d’)"""), "")
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .trim().trim(',', '.', ';', ' ')
        if (name.isEmpty()) name = raw
        return Ingredient(name = name, quantity = quantity, unit = unit, raw = raw)
    }

    fun parseAll(lines: List<String>): List<Ingredient> =
        lines.filter { it.isNotBlank() }.map(::parse)
}

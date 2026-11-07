package fr.mescourses.core.shopping

import fr.mescourses.core.ingredients.FoodName
import fr.mescourses.core.ingredients.IngredientParser
import fr.mescourses.core.model.Ingredient
import fr.mescourses.core.model.ItemSource
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.model.stripAccents
import kotlin.math.round

/**
 * Fusion déterministe (sans LLM) des ingrédients de recettes et des articles ajoutés à la main :
 * regroupe les doublons (« farine » + « Farine de blé » restent distincts, « oeufs » + « œuf » fusionnent),
 * convertit les unités compatibles (kg → g, cl/l → ml) et additionne les quantités.
 */
object ShoppingListMerger {

    private enum class Dimension { MASSE, VOLUME, AUTRE }

    private data class Normalized(val quantity: Double?, val unit: String?, val dimension: Dimension)

    private fun normalizeUnit(quantity: Double?, unit: String?): Normalized = when (unit) {
        "kg" -> Normalized(quantity?.times(1000), "g", Dimension.MASSE)
        "g" -> Normalized(quantity, "g", Dimension.MASSE)
        "mg" -> Normalized(quantity?.div(1000), "g", Dimension.MASSE)
        "l" -> Normalized(quantity?.times(1000), "ml", Dimension.VOLUME)
        "dl" -> Normalized(quantity?.times(100), "ml", Dimension.VOLUME)
        "cl" -> Normalized(quantity?.times(10), "ml", Dimension.VOLUME)
        "ml" -> Normalized(quantity, "ml", Dimension.VOLUME)
        else -> Normalized(quantity, unit, Dimension.AUTRE)
    }

    /** Clé de regroupement : minuscules, sans accents, mots au singulier. */
    fun key(name: String): String = name.lowercase().stripAccents()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { singular(it) }

    private fun singular(word: String): String = when {
        word.length <= 3 -> word
        word.endsWith("eaux") -> word.dropLast(1)
        word.endsWith("aux") && word.length > 4 -> word.dropLast(3) + "al"
        word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        word.endsWith("x") && !word.endsWith("ix") -> word.dropLast(1)
        else -> word
    }

    /** Ingrédients d'une recette ajustés à un nombre de portions (adultes + enfants × coefficient). */
    fun scale(recipe: Recipe, portions: Double): List<Ingredient> {
        val factor = if (recipe.servings > 0) portions / recipe.servings else 1.0
        return recipe.ingredients.map { it.copy(quantity = it.quantity?.times(factor)) }
    }

    /**
     * Articles de la liste pour une recette : l'aliment seul (« morceaux de poulet » → poulet),
     * sans les produits de placard (sel, huile, beurre…).
     */
    fun fromRecipe(recipe: Recipe, portions: Double): List<ShoppingItem> = scale(recipe, portions)
        .filterNot { Pantry.isStaple(it.name) }
        .map {
        ShoppingItem(
            name = FoodName.clean(it.name),
            quantity = it.quantity,
            unit = it.unit,
            source = ItemSource.RECETTE,
            origin = recipe.title,
        )
    }

    /** Article saisi librement : « 2 kg de pommes », « lessive »… */
    fun fromText(text: String): ShoppingItem {
        val ing = IngredientParser.parse(text)
        return ShoppingItem(name = ing.name, quantity = ing.quantity, unit = ing.unit, source = ItemSource.MANUEL)
    }

    /**
     * Fusionne une liste d'articles. Les articles cochés (déjà pris) sont ignorés.
     * Les articles de même nom mais d'unités incompatibles (ex. « 2 citrons » et « 1 c. à soupe de jus ») restent séparés.
     */
    fun merge(items: List<ShoppingItem>): List<ShoppingItem> {
        val groups = LinkedHashMap<String, MutableList<ShoppingItem>>()
        for (raw in items.filterNot { it.checked }) {
            // Œufs : jaunes, blancs, « œufs bio »… tout se retrouve dans une seule ligne, sans unité.
            val item = if (isEgg(raw.name)) raw.copy(name = "Œufs", unit = null) else raw
            val n = normalizeUnit(item.quantity, item.unit)
            val groupKey = key(item.name) + "|" + (n.unit ?: "")
            groups.getOrPut(groupKey) { mutableListOf() }.add(item.copy(quantity = n.quantity, unit = n.unit))
        }
        return groups.values.map { group ->
            val first = group.first()
            val quantities = group.mapNotNull { it.quantity }
            val total = if (quantities.isEmpty()) null else quantities.sum()
            val (q, u) = if (isEgg(first.name)) eggBoxes(total) to null else humanize(total, first.unit)
            ShoppingItem(
                id = first.id,
                name = first.name.replaceFirstChar { it.uppercase() },
                quantity = q,
                unit = u,
                source = if (group.any { it.source == ItemSource.RECETTE }) ItemSource.RECETTE else ItemSource.MANUEL,
                origin = group.mapNotNull { it.origin }.distinct().joinToString(", ").ifEmpty { null },
            )
        }
    }

    fun isEgg(name: String): Boolean = key(name).split(' ').any { it == "oeuf" }

    /** Les œufs s'achètent par boîte de 12 : 2 œufs → 12, 15 → 24. */
    fun eggBoxes(count: Double?): Double = 12.0 * kotlin.math.max(1.0, kotlin.math.ceil((count ?: 1.0) / 12 - 1e-9))

    /** Repasse en kg / l au-delà de 1000 et arrondit raisonnablement. */
    private fun humanize(quantity: Double?, unit: String?): Pair<Double?, String?> {
        if (quantity == null) return null to unit
        return when {
            unit == "g" && quantity >= 1000 -> roundTo(quantity / 1000, 2) to "kg"
            unit == "ml" && quantity >= 1000 -> roundTo(quantity / 1000, 2) to "l"
            unit == "g" || unit == "ml" -> round(quantity) to unit
            unit == null -> roundUpCount(quantity) to null
            else -> roundTo(quantity, 2) to unit
        }
    }

    /** « 1,5 oignon » → 2 oignons : on n'achète pas de demi-légume. */
    private fun roundUpCount(q: Double): Double = kotlin.math.ceil(q - 1e-9)

    private fun roundTo(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return round(value * factor) / factor
    }

    /** Affichage : « 1,5 kg Farine », « 3 Oeufs », « Sel ». */
    fun format(item: ShoppingItem): String {
        val q = item.quantity?.let { formatNumber(it) }
        return listOfNotNull(q, item.unit, item.name).joinToString(" ")
    }

    fun formatNumber(value: Double): String =
        if (value == round(value)) value.toLong().toString()
        else String.format(java.util.Locale.FRANCE, "%.2f", value).trimEnd('0').trimEnd(',')
}

package fr.mescourses.core.season

import fr.mescourses.core.shopping.ShoppingListMerger
import java.time.Month

/** Fruits et légumes de saison en France métropolitaine, mois par mois (calendrier usuel). */
object Seasonality {

    data class Season(val vegetables: List<String>, val fruits: List<String>)

    private val vegetables: Map<String, Set<Int>> = mapOf(
        "ail" to (6..9).toSet() + 10,
        "artichaut" to (5..9).toSet(),
        "asperge" to (4..6).toSet(),
        "aubergine" to (6..9).toSet(),
        "betterave" to (7..12).toSet() + (1..3),
        "blette" to (5..11).toSet(),
        "brocoli" to (6..11).toSet(),
        "carotte" to (1..12).toSet(),
        "céleri-rave" to (9..12).toSet() + (1..3),
        "champignon" to (9..11).toSet(),
        "chou de Bruxelles" to (10..12).toSet() + (1..2),
        "chou-fleur" to (9..12).toSet() + (1..4),
        "chou vert" to (10..12).toSet() + (1..3),
        "chou rouge" to (9..12).toSet() + (1..3),
        "concombre" to (5..9).toSet(),
        "courge (butternut, potimarron)" to (9..12).toSet() + (1..2),
        "courgette" to (6..9).toSet(),
        "endive" to (10..12).toSet() + (1..4),
        "épinard" to (3..5).toSet() + (9..11),
        "fenouil" to (6..11).toSet(),
        "fève" to (5..7).toSet(),
        "haricot vert" to (6..9).toSet(),
        "laitue" to (4..10).toSet(),
        "mâche" to (10..12).toSet() + (1..3),
        "navet" to (10..12).toSet() + (1..3),
        "oignon" to (1..12).toSet(),
        "panais" to (10..12).toSet() + (1..3),
        "petits pois" to (5..7).toSet(),
        "poireau" to (9..12).toSet() + (1..4),
        "poivron" to (7..10).toSet(),
        "pomme de terre" to (1..12).toSet(),
        "potiron" to (9..12).toSet(),
        "radis" to (4..7).toSet(),
        "salsifis" to (10..12).toSet() + (1..2),
        "tomate" to (6..9).toSet(),
        "topinambour" to (10..12).toSet() + (1..3),
    )

    private val fruits: Map<String, Set<Int>> = mapOf(
        "abricot" to (6..8).toSet(),
        "cassis" to (6..7).toSet(),
        "cerise" to (5..7).toSet(),
        "châtaigne" to (10..11).toSet(),
        "citron" to (11..12).toSet() + (1..4),
        "clémentine" to (11..12).toSet() + (1..2),
        "coing" to (10..11).toSet(),
        "figue" to (8..10).toSet(),
        "fraise" to (5..7).toSet(),
        "framboise" to (6..9).toSet(),
        "kiwi" to (11..12).toSet() + (1..3),
        "melon" to (6..9).toSet(),
        "mirabelle" to (8..9).toSet(),
        "myrtille" to (7..9).toSet(),
        "nectarine" to (6..9).toSet(),
        "orange" to (12..12).toSet() + (1..3),
        "pastèque" to (7..8).toSet(),
        "pêche" to (6..9).toSet(),
        "poire" to (8..12).toSet() + (1..2),
        "pomme" to (8..12).toSet() + (1..4),
        "prune" to (7..9).toSet(),
        "raisin" to (8..10).toSet(),
        "rhubarbe" to (4..6).toSet(),
    )

    /** Légumes et fruits vraiment « de saison » ce mois-ci (pas ceux qu'on trouve toute l'année), pour des idées de recherche. */
    fun highlights(month: Month): List<String> {
        val m = month.value
        return (vegetables + fruits).filter { (_, months) -> m in months && months.size <= 8 }.keys
            .map { it.substringBefore(" (").trim() }
            .distinct()
    }

    /** Légumes vraiment de saison ce mois-ci (ceux de [highlights], sans les fruits) : la base des recherches du menu. */
    fun seasonalVegetables(month: Month): List<String> {
        val m = month.value
        return vegetables.filter { (_, months) -> m in months && months.size <= 8 }.keys.map { it.substringBefore(" (").trim() }
    }

    /** Un produit du calendrier, reconnu dans un nom d'ingrédient par l'une de ses clés (mots entiers). */
    private class Produce(val name: String, val keys: List<String>, val months: Set<Int>)

    /** Stockés ou cultivés toute l'année en pratique : jamais comptés « hors saison ». */
    private val alwaysFine = setOf("ail", "champignon", "citron")

    private val produce: List<Produce> by lazy {
        (vegetables + fruits).filterKeys { it !in alwaysFine }.map { (name, months) ->
            // « courge (butternut, potimarron) » : la courge et ses variétés.
            val names = listOf(name.substringBefore(" (")) +
                name.substringAfter("(", "").substringBefore(")").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            Produce(name.substringBefore(" (").trim(), names.map { ShoppingListMerger.key(it) }, months)
        }.sortedByDescending { p -> p.keys.maxOf { it.length } }
    }

    /** Formes transformées ou conservées : la saison ne compte pas (concentré de tomate en hiver). */
    private val processed = Regex(
        """\b(concentre|coulis|sauce|pele|pelee|conserve|boite|seche|sechee|surgele|surgelee|confiture|compote|jus|poudre|vinaigre|moutarde|huile|sirop|bouillon|fecule|farine|flocon|chip|confit|zeste)\b""",
    )

    /**
     * Saison d'un ingrédient : true = de saison, false = hors saison, null = pas un fruit ou légume du calendrier.
     * « pommes de terre » n'est pas une pomme : le nom le plus long l'emporte.
     */
    fun inSeason(ingredient: String, month: Month): Boolean? = match(ingredient)?.let { month.value in it.months }

    private fun match(ingredient: String): Produce? {
        val key = " " + ShoppingListMerger.key(ingredient) + " "
        if (processed.containsMatchIn(key)) return null
        return produce.firstOrNull { p -> p.keys.any { " $it " in key } }
    }

    data class Report(
        /** Produits vraiment de saison (pas ceux qu'on trouve toute l'année). */
        val seasonal: List<String>,
        val outOfSeason: List<String>,
    )

    /** Bilan de saison d'une liste d'ingrédients. */
    fun report(ingredients: List<String>, month: Month): Report {
        val seasonal = LinkedHashSet<String>()
        val out = LinkedHashSet<String>()
        for (i in ingredients) {
            val p = match(i) ?: continue
            when {
                month.value !in p.months -> out += p.name
                p.months.size <= 8 -> seasonal += p.name
            }
        }
        return Report(seasonal.toList(), out.toList())
    }

    fun forMonth(month: Month): Season {
        val m = month.value
        return Season(
            vegetables = vegetables.filterValues { m in it }.keys.sorted(),
            fruits = fruits.filterValues { m in it }.keys.sorted(),
        )
    }
}

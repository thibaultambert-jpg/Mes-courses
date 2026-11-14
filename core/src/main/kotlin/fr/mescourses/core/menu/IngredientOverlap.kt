package fr.mescourses.core.menu

import fr.mescourses.core.ingredients.IngredientMapper
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.shopping.ShoppingListMerger

/**
 * Mesure la mutualisation des ingrédients d'un menu : ce qui sert à plusieurs plats (bon pour le
 * budget et le gaspillage) et ce qui n'est acheté que pour un seul plat.
 */
object IngredientOverlap {

    /** Un ingrédient et les numéros (à partir de 1) des plats qui l'utilisent. */
    data class Usage(val name: String, val dishes: List<Int>)

    data class Report(val shared: List<Usage>, val isolated: List<Usage>) {
        val hasData: Boolean get() = shared.isNotEmpty() || isolated.isNotEmpty()
    }

    /** Produits du placard : leur partage n'a pas d'intérêt, on les ignore. */
    private val pantry = setOf(
        "sel", "poivre", "huile", "huile olive", "huile d olive", "eau", "sucre", "farine", "vinaigre",
        "epice", "herbe de provence", "sel et poivre", "bouillon cube", "levure",
    )

    /** Clé de regroupement : « Poireaux » = « poireau », « crème fraîche épaisse » ≈ « crème fraîche ». */
    fun key(name: String): String = IngredientMapper.mapKey(rawKey(name))

    /** Clé calculée, avant les regroupements du mapping canonique. */
    fun rawKey(name: String): String = ShoppingListMerger.key(name)
        .replace(Regex("""\b(bio|frais|fraiche|fraiches|gros|grosse|petit|petite|epaisse|liquide|entier|entiere)\b"""), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun compute(dishes: List<MenuDish>): Report {
        val byKey = LinkedHashMap<String, Pair<String, MutableSet<Int>>>()
        dishes.forEachIndexed { i, dish ->
            dish.mainIngredients.forEach { raw ->
                val k = key(raw)
                if (k.isEmpty() || k in pantry) return@forEach
                byKey.getOrPut(k) { raw.trim().lowercase() to linkedSetOf() }.second += i + 1
            }
        }
        val usages = byKey.values.map { (name, set) -> Usage(name, set.sorted()) }
        return Report(
            shared = usages.filter { it.dishes.size >= 2 }.sortedByDescending { it.dishes.size },
            isolated = usages.filter { it.dishes.size == 1 },
        )
    }

    private const val SUMMARY_MAX = 6

    /** Bilan renvoyé au LLM après chaque modification du menu. */
    fun summary(report: Report): String {
        if (!report.hasData) return ""
        // Bilan borné : avec de vraies recettes, la liste complète remplirait le contexte du modèle.
        fun <T> short(list: List<T>, text: (T) -> String) =
            list.take(SUMMARY_MAX).joinToString(", ", transform = text) + if (list.size > SUMMARY_MAX) "… et ${list.size - SUMMARY_MAX} autres" else ""
        val shared = if (report.shared.isEmpty()) "aucun" else short(report.shared) { "${it.name} (plats ${it.dishes.joinToString(", ")})" }
        val isolated = short(report.isolated) { "${it.name} (plat ${it.dishes.single()})" }
        return buildString {
            append("Ingrédients partagés : $shared.")
            if (report.isolated.isNotEmpty()) {
                append(" Achetés pour un seul plat : $isolated.")
                if (report.isolated.size > report.shared.size) {
                    append(" Si possible, adapte un plat pour réutiliser un ingrédient déjà prévu.")
                }
            }
        }
    }
}

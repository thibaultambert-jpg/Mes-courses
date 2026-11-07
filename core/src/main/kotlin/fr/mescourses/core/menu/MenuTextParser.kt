package fr.mescourses.core.menu

/** Un plat lu dans une ligne « Titre — description — ingrédients : a, b, c ». */
data class ParsedDish(val title: String, val description: String, val ingredients: List<String> = emptyList())

/**
 * Lecture des plats écrits par le LLM, utilisée par l'outil propose_menu et, en plan B,
 * quand le modèle répond en texte au lieu d'appeler l'outil.
 */
object MenuTextParser {
    private val numbered = Regex("""^\s*(?:\d{1,2}\s*[.)\-:]|[-•*])\s+(.+)$""")
    private val ingredientsMarker = Regex("""(?i)\s*[—–\-(|,;]?\s*ingr[ée]dients?(?:\s+principaux)?\s*:\s*""")

    fun parse(text: String): List<ParsedDish> = text.lines()
        .mapNotNull { numbered.find(it)?.groupValues?.get(1) }
        .map { parseLine(it) }
        .filter { it.title.length in 3..80 }

    fun parseLine(line: String): ParsedDish {
        val clean = line.replace("**", "").replace("__", "").trim()
        val m = ingredientsMarker.find(clean)
        val head = if (m != null) clean.substring(0, m.range.first) else clean
        val ingredients = if (m != null) splitIngredients(clean.substring(m.range.last + 1)) else emptyList()
        val (title, description) = splitTitle(head)
        return ParsedDish(title, description, ingredients)
    }

    fun splitIngredients(text: String): List<String> = text.trim().trimEnd(')', '.')
        .split(',', ';', '/')
        .flatMap { it.split(Regex("""\s+et\s+""")) }
        .map { it.trim().trim('.', ')', '(') }
        .filter { it.length in 2..40 }

    /** « Titre — description », « Titre : description », « Titre - description ». */
    fun splitTitle(line: String): Pair<String, String> {
        val clean = line.replace("**", "").replace("__", "").trim().trimEnd('—', '–', '-', ' ')
        val separators = listOf(" — ", " – ", " - ", " : ", ": ")
        for (sep in separators) {
            val i = clean.indexOf(sep)
            if (i > 0) return clean.substring(0, i).trim() to clean.substring(i + sep.length).trim().trimEnd('—', '–', '-', ' ')
        }
        return clean.trimEnd('.') to ""
    }
}

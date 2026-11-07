package fr.mescourses.core.menu

/**
 * Plan B quand le modèle répond en texte au lieu d'appeler l'outil propose_menu :
 * récupère les lignes « 1. Titre — description » / « - **Titre** : description ».
 */
object MenuTextParser {
    private val numbered = Regex("""^\s*(?:\d{1,2}\s*[.)\-:]|[-•*])\s+(.+)$""")

    fun parse(text: String): List<Pair<String, String>> = text.lines()
        .mapNotNull { numbered.find(it)?.groupValues?.get(1) }
        .map { splitTitle(it) }
        .filter { it.first.length in 3..80 }

    /** « Titre — description », « Titre : description », « **Titre** - description ». */
    fun splitTitle(line: String): Pair<String, String> {
        val clean = line.replace("**", "").replace("__", "").trim()
        val separators = listOf(" — ", " – ", " - ", " : ", ": ")
        for (sep in separators) {
            val i = clean.indexOf(sep)
            if (i > 0) return clean.substring(0, i).trim() to clean.substring(i + sep.length).trim()
        }
        return clean.trimEnd('.') to ""
    }
}

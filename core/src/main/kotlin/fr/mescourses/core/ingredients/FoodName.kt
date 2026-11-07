package fr.mescourses.core.ingredients

/**
 * Réduit un libellé d'ingrédient à l'aliment à acheter :
 * « morceaux de poulet » → poulet, « quelques brins de persil frais bio » → persil, « jus de citron » → citron.
 */
object FoodName {

    /** Mots de quantité ou de contenant qui précèdent l'aliment (« brins de », « tête d'ail »…). */
    private val leading = Regex(
        """^(?i)(quelques|quelque|un peu de|un peu d['’]|un|une|des|du|de la|de l['’]|de|d['’]|la|le|les|l['’]|""" +
            """morceaux?|brins?|branches?|tiges?|tranches?|gousses?|t[eê]tes?|feuilles?|bottes?|bouquets?|pinc[ée]es?|""" +
            """zestes?|jus|filets?|blancs?|pav[ée]s?|dés|cubes?|lamelles?|rondelles?|bo[iî]tes?|pots?|sachets?|""" +
            """poign[ée]es?|bols?|verres?|noisettes?|noix|cuill[eè]res?|cuiller[ée]es?|c\. ?[àa] ?(soupe|caf[ée])|""" +
            """petits?|petites?|gros|grosses?|beaux?|belles?)\s+"""
    )

    /** Précisions de préparation sans intérêt pour les courses. */
    private val qualifiers = Regex(
        """(?i)(?<!\p{L})(fra[iî]ch(e|es)?|frais|bio|biologiques?|hach[ée]e?s?|[ée]minc[ée]e?s?|cisel[ée]e?s?|pel[ée]e?s?|""" +
            """[ée]pluch[ée]e?s?|[ée]goutt[ée]e?s?|finement|grossi[eè]rement|coup[ée]e?s?|""" +
            """en (d[ée]s|morceaux|rondelles|lamelles|cubes|tranches|julienne|quartiers|bâtonnets|batonnets|fines? tranches)|""" +
            """du moulin|de saison|[àa] temp[ée]rature ambiante|au go[uû]t|selon (le|votre) go[uû]t|facultatif)(?!\p{L})"""
    )

    fun clean(name: String): String {
        var s = name.replace(Regex("""\s*\([^)]*\)"""), " ").substringBefore(',').trim()
        // Retire les préfixes successifs : « quelques brins de » → « brins de » → « de » → aliment.
        var previous: String
        do {
            previous = s
            s = s.replace(leading, "").trim()
        } while (s != previous && s.isNotEmpty())
        s = s.replace(qualifiers, " ")
            .replace(Regex("""\s+(et|ou|de|d['’])\s*$"""), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', ';', '-')
        return s.ifEmpty { name.trim() }
    }
}

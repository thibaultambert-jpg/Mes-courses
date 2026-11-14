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
            """zestes?|jus|filets?|blancs?(?!\s+d['’]o?[eœ]ufs?)|pav[ée]s?|dés|cubes?|lamelles?|rondelles?|bo[iî]tes?|pots?|sachets?|""" +
            """poign[ée]es?|bols?|verres?|noisettes?|noix|cuill[eè]res?|cuiller[ée]es?|c\.? ?[àa] ?(soupe|caf[ée]|s|c)\.?|unit[ée]s?|pi[eè]ces?|""" +
            """cm|pouces?|[ée]clats?|quartiers?|c\.?\s*[àa]\s*(?:th[ée]|t)\.?|bou|paquets?|""" +
            """petits?|petites?|gros|grosses?|beaux?|belles?)\s+"""
    )

    /** Précisions de préparation sans intérêt pour les courses. */
    private val qualifiers = Regex(
        """(?i)(?<!\p{L})(fra[iî]ch(e|es)?|frais|bio|biologiques?|h[aâ]ch[ée]e?s?|[ée]minc[ée]e?s?|cisel[ée]e?s?|pel[ée]e?s?|""" +
            """[ée]pluch[ée]e?s?|[ée]goutt[ée]e?s?|finement|grossi[eè]rement|coup[ée]e?s?|""" +
            """en (d[ée]s|morceaux|rondelles|lamelles|cubes|tranches|julienne|quartiers|bâtonnets|batonnets|fines? tranches)|""" +
            """du moulin|de saison|[àa] temp[ée]rature ambiante|au go[uû]t|selon (le|votre) go[uû]t|facultatif)(?!\p{L})"""
    )

    /**
     * Préparations sans intérêt pour les courses (« fromage de chèvre émietté » → fromage de chèvre). Les produits
     * vendus tels quels gardent leur nom (voir [products]).
     */
    private val preparations = Regex(
        """(?i)(?<!\p{L})(bien\s+)?(""" +
            """[ée]miett[ée]e?s?|r[âa]p[ée]e?s?|fondus?|mix[ée]e?s?|[ée]cras[ée]e?s?|concass[ée]e?s?|effil[ée]e?s?|""" +
            """torr[ée]fi[ée]e?s?|grill[ée]e?s?|d[ée]noyaut[ée]e?s?|[ée]p[ée]pin[ée]e?s?|[ée]gren[ée]e?s?|ramollie?s?|""" +
            """battue?s?|tamis[ée]e?s?|ti[èe]des?|froide?s?|chaude?s?|m[ûu]re?s?|moyens?|moyennes?|enti[èe]re?s?|""" +
            """tranch[ée]e?s?|par[ée]e?s?|d[ée]soss[ée]e?s?|d[ée]cortiqu[ée]e?s?|[ée]queut[ée]e?s?|lav[ée]e?s?|""" +
            """d[ée]congel[ée]e?s?|r[ée]hydrat[ée]e?s?|cuite?s? al dente|taill[ée]e?s?|pr[ée]cuite?s?|""" +
            """de taille moyenne|sans (peau|ar[êe]tes?|os|noyaux?|p[ée]pins?)|avec (la )?peau|non trait[ée]e?s?|""" +
            """[àa] (la|le) (d[ée]coration|d[ée]co)|environ|bien|tr[èe]s)(?!\p{L})"""
    )

    /** Produits vendus sous ce nom : leur « préparation » fait partie du produit. */
    private val products = listOf(
        "fromage rape", "gruyere rape", "emmental rape", "comte rape", "parmesan rape", "mozzarella rapee",
        "chocolat fondu", "fromage fondu", "puree", "noix de coco rapee", "amandes effilees", "amande effilee",
        "jambon cuit", "pois casses", "tomates concassees", "tomate concassee", "chair a saucisse",
    )

    private fun key(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun clean(name: String): String {
        var s = name.replace(Regex("""\s*\([^)]*\)"""), " ").substringBefore(',').trim()
        // Compléments et alternatives : « … pour la décoration », « beurre ou margarine », « sucre + 1 pincée »…
        s = s.split(Regex("""(?i)\s+(pour|ou|\+|afin)\s+|\s*\+\s*|\s*/\s*""")).first().trim()
        // Retire les préfixes successifs : « quelques brins de » → « brins de » → « de » → aliment.
        var previous: String
        do {
            previous = s
            s = s.replace(leading, "").trim()
        } while (s != previous && s.isNotEmpty())
        s = s.replace(qualifiers, " ")
        if (products.none { key(s).startsWith(it) }) s = s.replace(preparations, " ")
        s = s
            .replace(Regex("""\s+(et|ou|de|d['’])\s*$"""), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', ';', '-')
        return s.ifEmpty { name.trim() }
    }
}

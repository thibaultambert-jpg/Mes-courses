package fr.mescourses.core.menu

import fr.mescourses.core.model.stripAccents

/**
 * Demandes courantes de « Ajuster le menu », comprises sans le modèle : un petit modèle sur téléphone se trompe
 * souvent d'outil (retire un plat au lieu d'ajouter des convives). Ce qui se lit sans ambiguïté est exécuté tel quel ;
 * tout le reste part au chef.
 */
sealed interface MenuCommand {
    /** [dish] : position du plat, à partir de 0. */
    data class AddGuests(val dish: Int, val adults: Int, val children: Int) : MenuCommand
    data class SetPeople(val dish: Int, val adults: Int, val children: Int) : MenuCommand
    data class Remove(val dish: Int) : MenuCommand
    /** [words] : ce que l'utilisateur veut (« curry de légumes ») ; null = la meilleure recette disponible. */
    /** [site] : identifiant du site voulu (« une recette de Jow »), s'il est nommé. */
    /** [course] : entrée ou dessert demandé ; null = plat principal. */
    data class AddDish(val words: String?, val site: String? = null, val course: fr.mescourses.core.catalog.Course? = null) : MenuCommand
    data class Replace(val dish: Int, val words: String?, val site: String? = null) : MenuCommand

    companion object {
        private val numbers = mapOf(
            "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10,
        )
        private val ordinals = mapOf(
            "premier" to 1, "1er" to 1, "deuxieme" to 2, "second" to 2, "troisieme" to 3, "quatrieme" to 4, "cinquieme" to 5,
            "sixieme" to 6, "septieme" to 7, "huitieme" to 8, "neuvieme" to 9, "dixieme" to 10,
        )
        private const val NUM = """(\d+|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix)"""
        private val add = Regex("""\b(r?ajoute[rsz]?|r?ajout|en plus|de plus)\b""")
        private val remove = Regex("""\b(supprime[rsz]?|retire[rsz]?|enleve[rsz]?|vire[rsz]?)\b""")
        private val replace = Regex("""\b(remplace[rsz]?|change[rsz]?|echange[rsz]?)\b""")
        private val negation = Regex("""\b(ne|n|pas|sans|jamais)\b""")
        private val people = Regex("""\b(adulte|enfant|personne|invite|convive)s?\b""")

        /** Noms des sites tels qu'on les écrit (« jow », « cuisine az », « hello fresh ») → identifiant. */
        private val sites: Map<String, String> = fr.mescourses.core.recipes.RecipeSites.all.flatMap { site ->
            val name = site.name.lowercase().stripAccents().replace(Regex("[^a-z0-9 ]"), " ").trim()
            listOf(name to site.id, name.replace(" ", "") to site.id)
        }.toMap() + mapOf("hello fresh" to "hellofresh", "ptit chef" to "ptitchef", "petit chef" to "ptitchef")

        private val filler = setOf("recette", "plat", "repas", "autre", "une", "un", "nouvelle", "nouveau", "site", "du", "de", "sur", "chez")

        private fun number(s: String): Int? = s.toIntOrNull() ?: numbers[s]

        private fun count(text: String, words: String): Int? =
            Regex("""\b$NUM (?:autres? )?(?:$words)s?\b""").find(text)?.let { number(it.groupValues[1]) }

        /** Plat désigné : « plat 2 », « 2e plat », « le deuxième plat », « dernier plat ». Position à partir de 0. */
        private fun dishRef(text: String, size: Int): Int? {
            val n = Regex("""\bplat (?:numero |n )?(\d+)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(\d+) ?(?:er|e|eme|ieme) plat\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: ordinals.entries.firstOrNull { Regex("""\b${it.key} plat\b""").containsMatchIn(text) }?.value
                ?: if (Regex("""\bdernier plat\b""").containsMatchIn(text)) size else null
            return n?.takeIf { it in 1..size }?.minus(1)
        }

        /** Ce qui suit « par », « avec », « de », « au(x) » après le mot « plat » : la recette voulue. */
        private fun wanted(text: String, after: Regex): String? =
            after.find(text)?.groupValues?.last()?.split(' ')?.dropWhile { it in filler }?.joinToString(" ")?.trim()
                ?.takeIf { it.length >= 3 && !people.containsMatchIn(it) }

        /** [size] : nombre de plats du menu. Renvoie null si la demande n'est pas claire : elle part alors au chef. */
        fun parse(message: String, size: Int): MenuCommand? {
            val full = message.lowercase().stripAccents().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            // Site nommé : on le retient et on l'enlève de la phrase.
            val named = sites.keys.sortedByDescending { it.length }.firstOrNull { Regex("\\b$it\\b").containsMatchIn(full) }
            val site = named?.let(sites::get)
            val text = if (named == null) full else full.replace(Regex("\\b(?:de |du |sur |chez |site )*$named\\b"), " ").replace(Regex("\\s+"), " ").trim()
            // « Je ne voulais pas supprimer un plat » : une phrase négative n'est pas un ordre.
            if (text.isEmpty() || negation.containsMatchIn(text) || text.split(' ').size > 14) return null
            val dish = dishRef(text, size)
            val isAdd = add.containsMatchIn(text)
            val isRemove = remove.containsMatchIn(text)
            val isReplace = replace.containsMatchIn(text)
            if (listOf(isAdd, isRemove, isReplace).count { it } > 1) return null

            if (people.containsMatchIn(text) && !isRemove && !isReplace) {
                if (dish == null) return null
                val adults = (count(text, "adulte") ?: 0) + (count(text, "personne|invite|convive") ?: 0)
                val children = count(text, "enfant") ?: 0
                if (adults + children == 0) return null
                // « pour 6 personnes », « à 6 personnes » donne un total ; « ajoute 2 adultes » s'ajoute aux convives.
                return if (isAdd) AddGuests(dish, adults, children) else SetPeople(dish, adults, children)
            }
            if (isRemove && dish != null && Regex("""\bplat\b""").containsMatchIn(text)) return Remove(dish)
            if (isReplace && dish != null) return Replace(dish, wanted(text, Regex("""\bpar (?:un |une |du |de la |des |le |la |les )?(.+)$""")), site)
            if (isAdd && dish == null && Regex("""\b(un|une|1) (?:autre |nouveau |nouvelle |\d+ ?(?:e|eme|ieme) |[a-z]+ieme )?(?:plat|recette|repas|entree|dessert)\b""").containsMatchIn(text)) {
                val course = when {
                    Regex("""\bdesserts?\b""").containsMatchIn(text) -> fr.mescourses.core.catalog.Course.DESSERT
                    Regex("""\bentrees?\b""").containsMatchIn(text) -> fr.mescourses.core.catalog.Course.ENTREE
                    else -> null
                }
                return AddDish(wanted(text, Regex("""\b(?:plat|recette|repas|entree|dessert) (?:de |d |avec |au |aux |a la |a base de )(?:du |de la |des |le |la |les )?(.+)$""")), site, course)
            }
            return null
        }
    }
}

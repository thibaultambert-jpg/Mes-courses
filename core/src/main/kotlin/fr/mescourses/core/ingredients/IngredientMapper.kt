package fr.mescourses.core.ingredients

import fr.mescourses.core.shopping.Aisle

/**
 * Mapping canonique des ingrédients (ressource `mapping-ingredients.json`) : ramène chaque clé d'ingrédient à sa
 * forme canonique, dit si une ligne doit être découpée ou ignorée, et porte les surcharges de rayon et de recherche
 * en magasin.
 *
 * Contrat seulement : cette version est l'identité (aucun mapping appliqué). L'implémentation réelle viendra
 * ensuite sans changer ces signatures.
 */
object IngredientMapper {
    /** Sort d'une clé ou d'une ligne : gardée telle quelle, à découper en plusieurs ingrédients, ou à ignorer. */
    enum class Status { OK, SPLIT, IGNORE }

    /** Correspondance d'une ligne entière : clé canonique (null si aucune) et statut (null si inconnu). */
    data class LineMapping(val key: String?, val status: Status?)

    /** Clé canonique d'une clé d'ingrédient ; la clé elle-même si elle n'est pas connue. */
    fun mapKey(key: String): String = key

    /** Statut d'une clé ; null si elle n'est pas connue. */
    fun status(key: String): Status? = null

    /** Correspondance d'une ligne d'ingrédient telle qu'écrite ; null si la ligne n'est pas connue. */
    fun mapLine(raw: String): LineMapping? = null

    /** Rayon imposé pour une clé, à la place de celui déduit du nom ; null si aucun. */
    fun aisleOverride(key: String): Aisle? = null

    /** Texte à chercher en magasin pour une clé, à la place de la clé ; null si aucun. */
    fun searchQueryOverride(key: String): String? = null

    /** Forme d'une ligne servant de clé de recherche dans le mapping des lignes. */
    fun lineText(raw: String): String = raw.trim().lowercase()
}

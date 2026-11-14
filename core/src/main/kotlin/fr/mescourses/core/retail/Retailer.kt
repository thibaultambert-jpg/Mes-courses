package fr.mescourses.core.retail

import fr.mescourses.core.cart.ProductMemory
import fr.mescourses.core.carrefour.Product

/** Enseignes de drive gérées par l'application. */
enum class Retailer(
    val label: String,
    val site: String,
    /** Application Android officielle de l'enseigne (ouverte depuis le panier si elle est installée). */
    val appPackage: String,
) {
    CARREFOUR("Carrefour", "carrefour.fr", "com.carrefour.fid.android"),
    LECLERC("E.Leclerc", "leclercdrive.fr", "com.infomil.leclercdrive"),
    AUCHAN("Auchan", "auchan.fr", "com.auchan.android");

    /** Auchan : drive ou livraison à domicile se choisissent sur auchan.fr, et se lisent dans le panier. */
    fun noStoreText(): String =
        if (this == AUCHAN) "Drive ou livraison : choisi sur auchan.fr (s'affiche dès qu'un article est au panier)" else "Aucun drive choisi"

    fun chooseStoreText(hasStore: Boolean): String = when {
        this == AUCHAN -> if (hasStore) "Changer sur auchan.fr" else "Choisir sur auchan.fr"
        hasStore -> "Changer de drive"
        else -> "Choisir mon drive"
    }

    /** Titre de la carte du compte : « Carrefour Drive », mais « Auchan » (drive ou livraison). */
    val accountTitle: String get() = if (this == AUCHAN) label else "$label Drive"

    companion object {
        fun parse(name: String?): Retailer = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CARREFOUR
    }
}

/**
 * Mémoire des choix propre à une enseigne : les identifiants produit de Leclerc et d'Auchan ne sont pas des EAN.
 * Carrefour garde les clés d'origine (sans préfixe) pour ne pas perdre les choix déjà enregistrés.
 */
class RetailerProductMemory(private val retailer: Retailer, private val inner: ProductMemory) : ProductMemory {
    private fun scoped(key: String) = if (retailer == Retailer.CARREFOUR) key else "${retailer.name.lowercase()}:$key"
    override suspend fun get(key: String): Product? = inner.get(scoped(key))
    override suspend fun remember(key: String, product: Product) = inner.remember(scoped(key), product)
}

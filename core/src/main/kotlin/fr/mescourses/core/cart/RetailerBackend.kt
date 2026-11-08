package fr.mescourses.core.cart

import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.Store
import fr.mescourses.core.carrefour.TimeSlot
import fr.mescourses.core.retail.Retailer

/**
 * Ce que l'app sait faire avec un drive. Implémenté nativement par enseigne (Carrefour, E.Leclerc, Auchan).
 * L'identifiant d'un produit (`Product.ean`, `CartLineItem.ean`) est l'EAN chez Carrefour, et l'identifiant
 * propre à l'enseigne chez Leclerc et Auchan.
 */
interface RetailerBackend {
    val retailer: Retailer
    val label: String get() = retailer.label
    suspend fun search(query: String, limit: Int = 5): List<Product>
    suspend fun cart(): Cart
    /** Ajoute `quantity` unités à ce qui est déjà dans le panier. */
    suspend fun addToCart(product: Product, quantity: Int): Cart
    /** Fixe la quantité totale (0 = retirer). */
    suspend fun setQuantity(ean: String, quantity: Int): Cart
    suspend fun timeslots(max: Int = 5): List<TimeSlot>

    /** Vérifie la session (exception SessionExpired / ChallengeRequired sinon) ; renvoie le panier s'il est lisible. */
    suspend fun sessionProbe(): Cart?

    /** Drives proches. Liste vide si l'enseigne ne sait pas les chercher depuis l'app (choix sur le site). */
    suspend fun stores(lat: Double, lon: Double, postalCode: String, city: String): List<Store> = emptyList()

    /** Oublie le drive connu (après un changement de drive). */
    fun forgetContext() {}
}

/** Mémoire des produits choisis par l'utilisateur pour chaque ingrédient (clé = ShoppingListMerger.key). */
interface ProductMemory {
    suspend fun get(key: String): Product?
    suspend fun remember(key: String, product: Product)
}

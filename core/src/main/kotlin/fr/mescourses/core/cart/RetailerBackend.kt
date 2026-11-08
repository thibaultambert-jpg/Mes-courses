package fr.mescourses.core.cart

import fr.mescourses.core.carrefour.Cart
import fr.mescourses.core.carrefour.Product
import fr.mescourses.core.carrefour.TimeSlot

/**
 * Ce que l'app sait faire avec un drive. Implémenté nativement par enseigne
 * (Carrefour aujourd'hui ; Leclerc et Auchan plus tard, sur le même modèle).
 */
interface RetailerBackend {
    val label: String
    /** Produits disponibles au drive. */
    suspend fun search(query: String, limit: Int = 5): List<Product>

    /** Comme [search], mais garde les produits en rupture (marqués `available = false`) pour les signaler. */
    suspend fun searchWithUnavailable(query: String, limit: Int = 8): List<Product> = search(query, limit)
    suspend fun cart(): Cart
    /** Ajoute `quantity` unités à ce qui est déjà dans le panier. */
    suspend fun addToCart(product: Product, quantity: Int): Cart
    /** Fixe la quantité totale (0 = retirer). */
    suspend fun setQuantity(ean: String, quantity: Int): Cart
    suspend fun timeslots(max: Int = 5): List<TimeSlot>

    /** Disponibilité au drive des produits (EAN → disponible) ; EAN absent = inconnu. */
    suspend fun availability(eans: List<String>): Map<String, Boolean> = emptyMap()

    /** EAN des articles du panier que le drive ne pourra pas servir. */
    suspend fun unavailableInCart(): List<String> = emptyList()
}

/** Mémoire des produits choisis par l'utilisateur pour chaque ingrédient (clé = ShoppingListMerger.key). */
interface ProductMemory {
    suspend fun get(key: String): Product?
    suspend fun remember(key: String, product: Product)
}

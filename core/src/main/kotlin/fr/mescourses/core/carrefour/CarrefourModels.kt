package fr.mescourses.core.carrefour

import kotlinx.serialization.Serializable

/** Identifiants du drive, lus dans le panier (exemples : 0678-150-7052, drive_clcv, 0678-150-149). */
@Serializable
data class DriveContext(
    val basketServiceId: String,
    val subBasketType: String = CarrefourRoutes.DEFAULT_SUB_BASKET,
    val facilityServiceId: String? = null,
)

@Serializable
data class CartLineItem(val ean: String, val name: String, val quantity: Int, val subtotal: Double? = null)

@Serializable
data class Cart(val lines: List<CartLineItem>, val total: Double?, val context: DriveContext?) {
    fun quantityOf(ean: String): Int = lines.firstOrNull { it.ean == ean }?.quantity ?: 0
}

@Serializable
data class Store(
    val id: String,
    val name: String,
    val address: String? = null,
    val city: String? = null,
    val distanceKm: Double? = null,
    val ref: String? = null,
)

@Serializable
data class TimeSlot(val date: String, val start: String, val end: String, val price: Double? = null, val available: Boolean = true)

package fr.mescourses.core.mcp

/** Enseignes gérées. `mcpKey` est la valeur attendue par les MCP multi-enseignes (ex. fr-grocery-mcp). */
enum class Retailer(val label: String, val mcpKey: String, val cartUrl: String) {
    CARREFOUR("Carrefour", "carrefour", "https://www.carrefour.fr/mon-panier"),
    LECLERC("E.Leclerc Drive", "leclerc", "https://www.e.leclerc/"),
    AUCHAN("Auchan", "auchan", "https://www.auchan.fr/checkout/cart/"),
}

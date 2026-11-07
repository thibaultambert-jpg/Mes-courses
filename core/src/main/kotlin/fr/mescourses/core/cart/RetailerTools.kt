package fr.mescourses.core.cart

import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.agent.params
import fr.mescourses.core.agent.str
import fr.mescourses.core.agent.toolError
import fr.mescourses.core.agent.toolOk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Outil exposé au LLM pour consulter les produits/prix du drive.
 * L'ajout au panier n'est volontairement PAS exposé au LLM : il passe par l'écran Panier,
 * où l'utilisateur valide chaque produit.
 */
class RetailerSearchTool(private val session: suspend () -> RetailerSession) : AgentTool {
    override val name = "retailer_search_product"
    override val description = "Cherche un produit et son prix dans le drive de l'enseigne choisie."
    override val parameters = params { string("query", "Produit recherché, ex. « crème fraîche épaisse »") }

    override suspend fun execute(args: JsonObject): JsonElement = try {
        val s = session()
        val products = s.search(args.str("query") ?: return toolError("query manquant"), limit = 5)
        toolOk("${products.size} produit(s) chez ${s.retailer.label}", buildJsonArray {
            products.forEach { p ->
                add(buildJsonObject {
                    put("nom", p.name)
                    p.brand?.let { put("marque", it) }
                    p.price?.let { put("prix", it) }
                    p.unitPrice?.let { put("prix_unitaire", it) }
                })
            }
        })
    } catch (e: Exception) {
        toolError("Drive indisponible : ${e.message}")
    }
}

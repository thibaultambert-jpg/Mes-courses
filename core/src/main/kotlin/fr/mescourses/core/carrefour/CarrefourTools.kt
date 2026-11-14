package fr.mescourses.core.carrefour

import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.agent.int
import fr.mescourses.core.agent.params
import fr.mescourses.core.agent.str
import fr.mescourses.core.agent.toolOk
import fr.mescourses.core.cart.RetailerBackend
import fr.mescourses.core.retail.Retailer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Écriture demandée par Gemma, à faire confirmer par l'utilisateur dans l'interface. */
data class WriteRequest(
    val ean: String,
    val productName: String,
    val price: Double?,
    val currentQuantity: Int,
    val newQuantity: Int,
)

/** L'interface affiche une carte « Confirmer / Annuler » et renvoie la décision. */
fun interface WriteConfirmer {
    suspend fun confirm(request: WriteRequest): Boolean
}

/**
 * Les outils du drive exposés à Gemma (mode Courses), pour l'enseigne active (Carrefour, E.Leclerc ou Auchan). Résultats compacts pour ménager le contexte
 * du modèle local. Les écritures passent TOUJOURS par [WriteConfirmer]. Aucun paiement, aucune validation
 * de commande, aucune réservation de créneau n'est exposé.
 */
class CarrefourTools(
    private val client: RetailerBackend,
    private val confirmer: WriteConfirmer,
) {
    private val who = client.label

    /** Le détail produit (composition, allergènes) n'existe que chez Carrefour pour l'instant. */
    fun all(): List<AgentTool> = listOfNotNull(search, details.takeIf { client is CarrefourClient }, viewCart, add, setQuantity, slots)

    /** Derniers produits montrés au modèle, pour retrouver nom et prix lors d'un ajout. */
    private val seen = LinkedHashMap<String, Product>()

    private fun tool(name: String, description: String, parameters: JsonObject, run: suspend (JsonObject) -> JsonElement) =
        object : AgentTool {
            override val name = name
            override val description = description
            override val parameters = parameters
            override suspend fun execute(args: JsonObject): JsonElement = try {
                run(args)
            } catch (e: CarrefourException) {
                buildJsonObject { put("erreur", e.code); put("message", e.message ?: "") }
            } catch (e: Exception) {
                buildJsonObject { put("erreur", "inattendue"); put("message", e.message ?: e.toString()) }
            }
        }

    private fun Product.compact() = buildJsonObject {
        put("id", ean)
        put("nom", name)
        brand?.let { put("marque", it) }
        price?.let { put("prix", it) }
        unitPrice?.let { put("prix_kg", it) }
        nutriscore?.let { put("nutriscore", it) }
        promo?.let { put("promo", it) }
        if (organic) put("bio", true)
    }

    val search = tool(
        "chercher_produit",
        "Cherche des produits chez $who.",
        params {
            string("requete", "Produit recherché, ex. « farine de blé bio »")
            integer("max", "Nombre de résultats (5 par défaut)", required = false)
        },
    ) { args ->
        val q = args.str("requete") ?: args.str("query") ?: return@tool buildJsonObject { put("erreur", "requete manquante") }
        val products = client.search(q, (args.int("max") ?: 5).coerceIn(1, 8))
        products.forEach { seen[it.ean] = it }
        buildJsonArray { products.forEach { add(it.compact()) } }
    }

    val details = tool(
        "details_produit",
        "Détails d'un produit : composition, allergènes, nutriscore.",
        params { string("id", "Identifiant (EAN) du produit") },
    ) { args ->
        val id = args.str("id") ?: return@tool buildJsonObject { put("erreur", "id manquant") }
        val d = (client as? CarrefourClient)?.productDetail(id) ?: return@tool buildJsonObject { put("erreur", "introuvable") }
        buildJsonObject {
            put("id", d.product.ean); put("nom", d.product.name)
            d.product.price?.let { put("prix", it) }
            d.ingredients?.let { put("composition", it.take(300)) }
            d.allergens?.let { put("allergenes", it.take(150)) }
            d.product.nutriscore?.let { put("nutriscore", it) }
        }
    }

    val viewCart = tool("voir_panier", "Affiche le panier $who actuel.", params { }) { _ -> client.cart().compact() }

    private suspend fun write(ean: String, newQuantity: Int, current: Int): JsonElement {
        val product = seen[ean]
        val ok = confirmer.confirm(WriteRequest(ean, product?.name ?: ean, product?.price, current, newQuantity))
        if (!ok) return buildJsonObject { put("ok", false); put("message", "L'utilisateur a annulé.") }
        val cart = client.setQuantity(ean, newQuantity)
        return buildJsonObject {
            put("ok", true)
            put("message", "Confirmé par l'utilisateur")
            cart.total?.let { put("panier_total", it) }
        }
    }

    val add = tool(
        "ajouter_au_panier",
        "Ajoute un produit au panier (l'utilisateur doit confirmer).",
        params {
            string("id", "Identifiant du produit, tel que renvoyé par chercher_produit")
            integer("quantite", "Nombre d'unités à ajouter", required = false)
        },
    ) { args ->
        val id = args.str("id") ?: return@tool buildJsonObject { put("erreur", "id manquant") }
        val qty = (args.int("quantite") ?: 1).coerceIn(1, 20)
        val current = client.cart().quantityOf(id)
        write(id, current + qty, current)
    }

    val setQuantity = tool(
        "modifier_quantite",
        "Change la quantité d'un produit du panier (0 = retirer ; l'utilisateur doit confirmer).",
        params {
            string("id", "Identifiant du produit")
            integer("quantite", "Nouvelle quantité totale (0 pour retirer)")
        },
    ) { args ->
        val id = args.str("id") ?: return@tool buildJsonObject { put("erreur", "id manquant") }
        val qty = (args.int("quantite") ?: return@tool buildJsonObject { put("erreur", "quantite manquante") }).coerceIn(0, 50)
        val cart = client.cart()
        cart.lines.firstOrNull { it.ean == id }?.let { line ->
            seen.putIfAbsent(id, Product(id = id, name = line.name, identifiers = mapOf("ean" to id)))
        }
        write(id, qty, cart.quantityOf(id))
    }

    val slots = tool("creneaux_drive", "Les 5 prochains créneaux de retrait au drive (lecture seule).", params { }) { _ ->
        val list = client.timeslots(5)
        buildJsonArray {
            list.forEach { s ->
                add(buildJsonObject {
                    put("date", s.date); put("debut", s.start); put("fin", s.end)
                    s.price?.let { put("prix", it) }
                })
            }
        }
    }
}

/** Représentation compacte du panier pour le LLM (outils Courses et report de contexte). */
fun Cart.compact(): JsonObject = buildJsonObject {
    put("lignes", buildJsonArray {
        lines.forEach { l ->
            add(buildJsonObject {
                put("id", l.ean); put("nom", l.name); put("qte", l.quantity)
                l.subtotal?.let { put("sous_total", it) }
            })
        }
    })
    total?.let { put("total", it) }
}

/** Prompt système du mode Courses. */
object ShoppingPrompt {
    fun system(preferOrganic: Boolean, preferences: String = "", retailer: Retailer = Retailer.CARREFOUR): String = buildString {
        val who = retailer.label
        appendLine("Tu es « Mes courses », un assistant qui aide à remplir le panier $who Drive. Tu parles français, brièvement.")
        appendLine("Outils :")
        appendLine("- chercher_produit : pour trouver un produit (une recherche par ingrédient, termes simples).")
        if (retailer == Retailer.CARREFOUR) appendLine("- details_produit : seulement si l'utilisateur demande la composition ou les allergènes.")
        appendLine("- voir_panier : pour savoir ce qui est déjà dans le panier.")
        appendLine("- ajouter_au_panier / modifier_quantite : l'utilisateur confirme chaque ajout dans l'application.")
        appendLine("- creneaux_drive : pour indiquer les prochains créneaux de retrait (lecture seule).")
        appendLine("Règles :")
        appendLine("- Propose AU PLUS 3 produits pour un besoin, avec prix, puis ajoute celui choisi (ou le plus pertinent si l'utilisateur te laisse choisir).")
        if (preferOrganic) appendLine("- Privilégie les produits bio quand ils existent.")
        if (preferences.isNotBlank()) appendLine("- Préférences du foyer : $preferences")
        appendLine("- Tu ne passes JAMAIS commande, ne payes jamais et ne réserves jamais de créneau : l'utilisateur finalise sur ${retailer.site}.")
        appendLine("- Si un outil renvoie une erreur de session, dis à l'utilisateur de se reconnecter à $who.")
    }
}

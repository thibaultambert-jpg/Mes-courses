package fr.mescourses.app.retail

import fr.mescourses.app.data.Settings
import fr.mescourses.core.auchan.AuchanRoutes
import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.core.leclerc.LeclercRoutes
import fr.mescourses.core.leclerc.LeclercStore
import fr.mescourses.core.retail.Retailer

/** Adresses utiles du site d'une enseigne, pour les WebViews (invisible et visible). */
class RetailerSite(
    val retailer: Retailer,
    /** Page où garer la WebView invisible : les requêtes partent de cette page (cookies, anti-robot). */
    val parking: () -> String,
    /** Page à charger avant une requête vers `url` (une autre origine peut l'exiger) ; par défaut, [parking]. */
    val pageFor: (url: String) -> String = { parking() },
    /** Page de connexion ouverte dans la WebView visible. */
    val login: () -> String,
    /** Panier du site, pour finaliser (créneau, paiement) à la main. */
    val webCart: () -> String,
    /** Page où l'on choisit son drive sur le site. */
    val storeChoice: () -> String,
)

object RetailerSites {
    fun create(settings: () -> Settings): Map<Retailer, RetailerSite> = mapOf(
        Retailer.CARREFOUR to RetailerSite(
            Retailer.CARREFOUR,
            parking = { CarrefourRoutes.PARKING },
            login = { CarrefourRoutes.AUTHORIZE_URL },
            webCart = { CarrefourRoutes.WEB_CART },
            storeChoice = { CarrefourRoutes.PARKING },
        ),
        Retailer.LECLERC to RetailerSite(
            Retailer.LECLERC,
            // Les requêtes du drive partent de la page du drive (même hôte fdN-courses) ; la recherche de magasins
            // part de www.leclercdrive.fr (seule origine autorisée par son API).
            parking = { settings().leclercStore()?.let(LeclercRoutes::storePage) ?: LeclercRoutes.HOME },
            pageFor = { url ->
                if (LeclercRoutes.isLocator(url)) LeclercRoutes.HOME
                else settings().leclercStore()?.let(LeclercRoutes::storePage) ?: LeclercRoutes.HOME
            },
            login = { settings().leclercStore()?.let(LeclercRoutes::storePage) ?: LeclercRoutes.HOME },
            webCart = { settings().leclercStore()?.let(LeclercRoutes::storePage) ?: LeclercRoutes.HOME },
            storeChoice = { settings().leclercStore()?.let(LeclercRoutes::storePage) ?: LeclercRoutes.HOME },
        ),
        Retailer.AUCHAN to RetailerSite(
            Retailer.AUCHAN,
            // La page panier appelle l'API panier du site : c'est là qu'on relève ses jetons.
            parking = { AuchanRoutes.CART_PAGE },
            login = { AuchanRoutes.HOME },
            webCart = { AuchanRoutes.CART_PAGE },
            storeChoice = { AuchanRoutes.HOME },
        ),
    )

    /** Drive Leclerc lu dans l'adresse d'une page du site (…fd9-courses.leclercdrive.fr/magasin-053701-053701-Nom/…). */
    fun leclercStoreFromUrl(url: String?): LeclercStore? = fr.mescourses.core.leclerc.LeclercParsers.storeFromUrl(url)
}

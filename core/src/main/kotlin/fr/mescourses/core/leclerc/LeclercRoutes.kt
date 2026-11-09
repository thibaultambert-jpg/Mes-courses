package fr.mescourses.core.leclerc

import java.net.URLEncoder

/**
 * TOUTES les routes E.Leclerc Drive utilisées par l'app (intégration non officielle, usage personnel).
 * Documentation et sources : docs/leclerc/ROUTES_LECLERC.md. Si le site change, c'est ici qu'il faut corriger.
 *
 * Chaque drive a son propre hôte (`fd8-courses…`, `fd9-courses…`, `fd14-courses…`) et son chemin
 * `magasin-{noPL}-{noPR}` ; recherche et panier se font sur cet hôte, depuis une page de ce même hôte.
 */
object LeclercRoutes {
    const val ORIGIN = "https://www.leclercdrive.fr"
    /** Page où « garer » la WebView tant qu'aucun drive n'est choisi (et pour la recherche de magasins). */
    const val HOME = "$ORIGIN/"

    private const val LOCATOR = "https://api-recherchemagasins.leclercdrive.fr/API_RechercheMagasins/api/v1"
    fun locatorAutocomplete(query: String) = "$LOCATOR/autocomplete?search=${enc(query)}&provider=Woosmap"
    fun locatorCoordinates(id: String) = "$LOCATOR/autocomplete/coordinates?id=${enc(id)}&provider=Woosmap"
    fun locatorNearby(lat: Double, lon: Double, postalCode: String) = "$LOCATOR/MapPoint/nearby?latitude=$lat&longitude=$lon&postalCode=${enc(postalCode)}"
    fun isLocator(url: String) = url.startsWith(LOCATOR)

    /** Racine du drive, ex. https://fd9-courses.leclercdrive.fr/magasin-053701-053701 */
    fun storeBase(store: LeclercStore) = "https://${store.host}/${store.base}"
    /** Page d'accueil du drive (stationnement de la WebView, choix du drive sur le site, connexion). */
    fun storePage(store: LeclercStore) = store.homeUrl
    fun search(store: LeclercStore, query: String) = storeBase(store) + "/recherche.aspx?TexteRecherche=${enc(query)}"
    /**
     * Écriture du panier : chemin court `magasin-{noPL}-{noPR}`, SANS le nom de la ville (sinon 404), corps JSON.
     * Relevé sur le site (Chrome, novembre 2026) : POST …/magasin-159201-159201/panier.aspx?op=1.
     */
    fun cartOp(store: LeclercStore) = "https://${store.host}/magasin-${store.id}-${store.noPR}/panier.aspx?op=1"
    /** Recherche sans résultat : la page ne contient alors que les produits du panier. */
    fun cartPage(store: LeclercStore) = search(store, NO_MATCH_QUERY)
    const val NO_MATCH_QUERY = "zzzznomatchzzz"

    /** Marqueur présent dans toute vraie page de drive (résumé du panier). Absent des pages DataDome et « session expirée ». */
    const val STORE_PAGE_MARKER = "lstProduitsLight"

    val HTML_HEADERS = mapOf("accept" to "text/html,application/xhtml+xml")
    val JSON_HEADERS = mapOf("accept" to "application/json")
    val CART_HEADERS = mapOf(
        "accept" to "application/json, text/javascript, */*; q=0.01",
        "content-type" to "application/json; charset=UTF-8",
        "x-requested-with" to "XMLHttpRequest",
    )

    /**
     * Dans une WebView, `html` et `body` du site valent 0 px de haut : les volets (connexion, choix du service et du
     * drive) qui prennent leur hauteur en pourcentage sont rognés et la page paraît blanche. Ce script redonne à
     * `html`/`body` la hauteur de l'écran, et met en plein écran toute fenêtre active restée sans hauteur (à chaque
     * ouverture : ces volets s'ouvrent sans changer de page).
     */
    const val LAYOUT_FIX_JS = """(function(){if(window.__mcLayoutFix)return;window.__mcLayoutFix=1;var st=document.createElement('style');st.id='mc-layout-fix';st.textContent='html,body{height:100%!important;min-height:100%!important}';(document.head||document.documentElement).appendChild(st);function fix(){document.querySelectorAll('.modal.modal-active').forEach(function(m){if(m.getBoundingClientRect().height>=50)return;var s=m.style;[['position','fixed'],['top','0'],['bottom','0'],['left','0'],['right','0'],['height','auto'],['max-height','none'],['overflow-y','auto']].forEach(function(p){s.setProperty(p[0],p[1],'important');});m.setAttribute('data-mc-fix','1');m.scrollTop=0;});}fix();new MutationObserver(fix).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','style']});})()"""

    /**
     * Vérification anti-robot (DataDome) : la page ne contient qu'un cadre `geo.captcha-delivery.com` en `100vh`, qui
     * peut s'afficher avec une hauteur nulle dans la WebView (page blanche). On lui impose la taille de l'écran en pixels.
     */
    const val CAPTCHA_FIX_JS = """(function(){if(window.__mcCaptchaFix)return;window.__mcCaptchaFix=1;function fix(){var f=document.querySelectorAll('iframe[src*="captcha-delivery"]');if(!f.length)return;var w=Math.max(innerWidth,document.documentElement.clientWidth||0,320),h=Math.max(innerHeight,document.documentElement.clientHeight||0,480);var m=document.querySelector('meta[name=viewport]');if(m&&m.getAttribute('content')==='width=1280')m.setAttribute('content','width=device-width, initial-scale=1');[document.documentElement,document.body].forEach(function(e){if(!e)return;e.style.setProperty('height',h+'px','important');e.style.setProperty('min-height',h+'px','important');e.style.setProperty('margin','0','important');e.style.setProperty('background','#fff','important');});f.forEach(function(i){var s=i.style;[['position','fixed'],['top','0'],['left','0'],['display','block'],['visibility','visible'],['opacity','1'],['border','0'],['width',w+'px'],['height',h+'px'],['min-height',h+'px'],['max-height','none'],['z-index','2147483647']].forEach(function(p){if(s.getPropertyValue(p[0])!==p[1]||s.getPropertyPriority(p[0])!=='important')s.setProperty(p[0],p[1],'important');});i.setAttribute('data-mc-fix','1');});}fix();addEventListener('resize',fix);new MutationObserver(fix).observe(document.documentElement,{subtree:true,childList:true});})()"""

    /** Hauteur du cadre anti-robot DataDome, -1 s'il n'y en a pas. */
    const val CAPTCHA_PROBE_JS = """(function(){var f=document.querySelector('iframe[src*="captcha-delivery"]');return f?Math.round(f.getBoundingClientRect().height):-1;})()"""

    /**
     * Mode « ordinateur » : `www.leclercdrive.fr` est adaptative et choisit sa version mobile d'après la largeur de
     * l'écran (`meta viewport` = largeur de l'appareil), quel que soit le navigateur annoncé. On impose une largeur de
     * bureau pour obtenir la version ordinateur (vue d'ensemble et zoom au doigt dans la WebView).
     */
    const val DESKTOP_VIEWPORT_JS = """(function(){if(document.querySelector('iframe[src*="captcha-delivery"]'))return;var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}if(m.getAttribute('content')!=='width=1280')m.setAttribute('content','width=1280');})()"""

    /** Jamais appelées : commande, paiement, réservation de créneau. */
    val FORBIDDEN = listOf("/commande", "/paiement", "/validation", "/creneau")

    /** Type d'action du panier : 1 = ajouter / augmenter, 2 = diminuer / retirer (quantité cible absolue). */
    const val ACTION_ADD = 1
    const val ACTION_SUB = 2

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
}

/**
 * Drive Leclerc choisi : numéro (noPL), point de retrait (noPR, égal pour un drive), hôte des courses, nom, et
 * `path`, le segment exact de l'adresse (`magasin-159201-159201-Colombes-Michelet`) : sans le nom de la ville,
 * certains drives répondent « page introuvable ».
 */
data class LeclercStore(
    val id: String,
    val noPR: String = id,
    val host: String,
    val name: String = "",
    val path: String = "magasin-$id-$noPR",
) {
    /** Racine des pages du drive : le chemin sans « .aspx » ni « / » final (`magasin-159201-159201-colombes-michelet`). */
    val base: String get() = path.trimEnd('/').removeSuffix(".aspx").removeSuffix(".ASPX")

    /** Page d'accueil telle que donnée par le site (`…/magasin-…-colombes-michelet.aspx`), sinon `…/base/`. */
    val homeUrl: String get() = "https://$host/" + if (path.endsWith(".aspx", ignoreCase = true)) path else "$base/"
}

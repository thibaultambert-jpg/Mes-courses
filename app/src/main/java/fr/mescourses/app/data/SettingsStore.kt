package fr.mescourses.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.mescourses.core.carrefour.DriveContext
import fr.mescourses.core.leclerc.LeclercStore
import fr.mescourses.core.retail.Retailer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val modelPath: String = "",
    val preferGpu: Boolean = true,
    /** Bloc « Bienvenue » de l'écran Menu replié. */
    val welcomeCollapsed: Boolean = false,
    val hfToken: String = "",
    /** Adultes par défaut pour chaque repas. */
    val householdSize: Int = 2,
    val children: Int = 0,
    /** Plats par menu ; les entrées et desserts s'ajoutent à côté. */
    val meals: Int = 5,
    val starters: Int = 0,
    val desserts: Int = 0,
    /** Part d'un enfant par rapport à un adulte (0,5 = demi-portion). */
    val childFactor: Double = 0.5,
    val preferOrganic: Boolean = true,
    val preferences: String = "",
    /** Enseigne préférée : utilisée par défaut pour le panier et l'assistant (nom de [Retailer]). */
    val retailer: String = Retailer.CARREFOUR.name,
    /** Drive E.Leclerc choisi : numéro, point de retrait, hôte des courses (fdN-courses.leclercdrive.fr) et nom. */
    val leclercStoreId: String = "",
    val leclercNoPR: String = "",
    val leclercHost: String = "",
    val leclercStoreName: String = "",
    /** Segment exact de l'adresse du drive Leclerc (`magasin-159201-159201-Colombes-Michelet`). */
    val leclercPath: String = "",
    /** Magasin Auchan, lu dans le panier (le drive se choisit sur auchan.fr). */
    val auchanStoreName: String = "",
    /** Produits « toujours dans mon placard », séparés par des retours à la ligne ; vide = liste par défaut. */
    val pantry: String = "",
    /** Recherches corrigées au drive, une par ligne : « clé de l'ingrédient<TAB>recherche ». */
    val searchQueries: String = "",
    /**
     * Leclerc : navigateur annoncé au site. « ordinateur » (défaut) = site ordinateur fdN-courses, le seul que l'app sait
     * lire ; « mobile » = Chrome Android (site mobile m-courses) ; « webview » = User-Agent d'origine.
     */
    val leclercUaMode: String = "ordinateur",
    /** Drive Carrefour choisi dans l'app. */
    val storeId: String = "",
    val storeName: String = "",
    /** Référence du magasin (pour vérifier les indisponibles du panier). */
    val storeRef: String = "",
    /** Contexte du drive lu dans le panier Carrefour (mémorisé pour les fois suivantes). */
    val basketServiceId: String = "",
    val subBasketType: String = "",
    val facilityServiceId: String = "",
    /** Debug : WebView présentée comme un Chrome normal (sans « ; wv » dans le User-Agent). */
    val uaWithoutWv: Boolean = false,
    /** Dernier formulaire « envies de la semaine » (MenuWishes en JSON), repris la semaine suivante. */
    val menuWishes: String = "",
    /** Taille de contexte maximale demandée au moteur (0 = Auto selon le modèle). */
    val contextTokens: Int = 0,
) {
    val preferredRetailer: Retailer get() = Retailer.parse(retailer)

    /** Placard : liste par défaut tant que l'utilisateur ne l'a pas modifiée (« - » = placard vide). */
    fun pantryList(): List<String> = when (pantry) {
        "" -> fr.mescourses.core.shopping.Pantry.DEFAULTS
        "-" -> emptyList()
        // Les épices restent toujours au placard (demande de l'utilisateur).
        else -> pantry.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .let { l -> if (l.any { it.equals("Épices", true) || it.equals("Epices", true) }) l else l + "Épices" }
    }

    fun queryMap(): Map<String, String> = searchQueries.split('\n').mapNotNull { line ->
        line.split('\t').takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { it[0] to it[1] }
    }.toMap()

    /** Mémorise (ou oublie, si vide) la recherche corrigée d'un ingrédient. */
    fun withQuery(key: String, query: String): Settings {
        val m = queryMap().toMutableMap()
        val q = query.replace(Regex("[\\t\\n]"), " ").trim()
        if (q.isEmpty()) m.remove(key) else m[key] = q
        return copy(searchQueries = m.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    fun withPantry(list: List<String>): Settings = copy(pantry = if (list.isEmpty()) "-" else list.joinToString("\n"))

    fun leclercStore(): LeclercStore? = if (leclercStoreId.isBlank() || leclercHost.isBlank()) null
        else leclercNoPR.ifBlank { leclercStoreId }.let { noPR ->
            LeclercStore(leclercStoreId, noPR, leclercHost, leclercStoreName, leclercPath.ifBlank { "magasin-$leclercStoreId-$noPR" })
        }

    /** Nom du drive choisi pour une enseigne (vide si aucun). */
    fun storeName(r: Retailer): String = when (r) {
        Retailer.CARREFOUR -> storeName
        Retailer.LECLERC -> leclercStoreName.ifBlank { leclercStoreId.takeIf { it.isNotBlank() }?.let { "Drive $it" }.orEmpty() }
        Retailer.AUCHAN -> auchanStoreName
    }

    fun driveContext(): DriveContext? = basketServiceId.takeIf { it.isNotBlank() }?.let {
        DriveContext(it, subBasketType.ifBlank { fr.mescourses.core.carrefour.CarrefourRoutes.DEFAULT_SUB_BASKET }, facilityServiceId.ifBlank { null })
    }
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val modelPath = stringPreferencesKey("model_path")
        val preferGpu = booleanPreferencesKey("prefer_gpu")
        val welcomeCollapsed = booleanPreferencesKey("welcome_collapsed")
        val hfToken = stringPreferencesKey("hf_token")
        val household = intPreferencesKey("household")
        val children = intPreferencesKey("children")
        val meals = intPreferencesKey("meals")
        val starters = intPreferencesKey("starters")
        val desserts = intPreferencesKey("desserts")
        val childFactor = androidx.datastore.preferences.core.doublePreferencesKey("child_factor")
        val organic = booleanPreferencesKey("prefer_organic")
        val preferences = stringPreferencesKey("preferences")
        val storeId = stringPreferencesKey("cf_store_id")
        val storeName = stringPreferencesKey("cf_store_name")
        val storeRef = stringPreferencesKey("cf_store_ref")
        val basket = stringPreferencesKey("cf_basket_service")
        val subBasket = stringPreferencesKey("cf_sub_basket")
        val facility = stringPreferencesKey("cf_facility_service")
        val uaWithoutWv = booleanPreferencesKey("cf_ua_without_wv")
        val retailer = stringPreferencesKey("retailer")
        val lcStore = stringPreferencesKey("lc_store_id")
        val lcNoPR = stringPreferencesKey("lc_store_nopr")
        val lcHost = stringPreferencesKey("lc_host")
        val lcName = stringPreferencesKey("lc_store_name")
        val lcPath = stringPreferencesKey("lc_store_path")
        val auName = stringPreferencesKey("au_store_name")
        val pantry = stringPreferencesKey("pantry")
        val searchQueries = stringPreferencesKey("search_queries")
        val lcUaMode = stringPreferencesKey("lc_ua_mode")
        val menuWishes = stringPreferencesKey("menu_wishes")
        val contextTokens = intPreferencesKey("context_tokens")
    }

    private fun Preferences.toSettings() = Settings(
        modelPath = this[K.modelPath].orEmpty(),
        preferGpu = this[K.preferGpu] ?: true,
        welcomeCollapsed = this[K.welcomeCollapsed] ?: false,
        hfToken = this[K.hfToken].orEmpty(),
        householdSize = this[K.household] ?: 2,
        children = this[K.children] ?: 0,
        meals = this[K.meals] ?: 5,
        starters = this[K.starters] ?: 0,
        desserts = this[K.desserts] ?: 0,
        childFactor = this[K.childFactor] ?: 0.5,
        preferOrganic = this[K.organic] ?: true,
        preferences = this[K.preferences].orEmpty(),
        storeId = this[K.storeId].orEmpty(),
        storeName = this[K.storeName].orEmpty(),
        storeRef = this[K.storeRef].orEmpty(),
        basketServiceId = this[K.basket].orEmpty(),
        subBasketType = this[K.subBasket].orEmpty(),
        facilityServiceId = this[K.facility].orEmpty(),
        uaWithoutWv = this[K.uaWithoutWv] ?: false,
        retailer = this[K.retailer] ?: Retailer.CARREFOUR.name,
        leclercStoreId = this[K.lcStore].orEmpty(),
        leclercNoPR = this[K.lcNoPR].orEmpty(),
        leclercHost = this[K.lcHost].orEmpty(),
        leclercStoreName = this[K.lcName].orEmpty(),
        leclercPath = this[K.lcPath].orEmpty(),
        auchanStoreName = this[K.auName].orEmpty(),
        pantry = this[K.pantry].orEmpty(),
        searchQueries = this[K.searchQueries].orEmpty(),
        leclercUaMode = this[K.lcUaMode] ?: "ordinateur",
        menuWishes = this[K.menuWishes].orEmpty(),
        contextTokens = this[K.contextTokens] ?: 0,
    )

    val state: StateFlow<Settings> = context.dataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    val current: Settings get() = state.value

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.modelPath] = s.modelPath
            prefs[K.preferGpu] = s.preferGpu
            prefs[K.welcomeCollapsed] = s.welcomeCollapsed
            prefs[K.hfToken] = s.hfToken
            prefs[K.household] = s.householdSize
            prefs[K.children] = s.children
            prefs[K.meals] = s.meals
            prefs[K.starters] = s.starters
            prefs[K.desserts] = s.desserts
            prefs[K.childFactor] = s.childFactor
            prefs[K.organic] = s.preferOrganic
            prefs[K.preferences] = s.preferences
            prefs[K.storeId] = s.storeId
            prefs[K.storeName] = s.storeName
            prefs[K.storeRef] = s.storeRef
            prefs[K.basket] = s.basketServiceId
            prefs[K.subBasket] = s.subBasketType
            prefs[K.facility] = s.facilityServiceId
            prefs[K.uaWithoutWv] = s.uaWithoutWv
            prefs[K.retailer] = s.retailer
            prefs[K.lcStore] = s.leclercStoreId
            prefs[K.lcNoPR] = s.leclercNoPR
            prefs[K.lcHost] = s.leclercHost
            prefs[K.lcName] = s.leclercStoreName
            prefs[K.lcPath] = s.leclercPath
            prefs[K.auName] = s.auchanStoreName
            prefs[K.pantry] = s.pantry
            prefs[K.searchQueries] = s.searchQueries
            prefs[K.lcUaMode] = s.leclercUaMode
            prefs[K.menuWishes] = s.menuWishes
            prefs[K.contextTokens] = s.contextTokens
        }
    }
}

package fr.mescourses.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.mescourses.core.carrefour.DriveContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val modelPath: String = "",
    val preferGpu: Boolean = true,
    val hfToken: String = "",
    /** Adultes par défaut pour chaque repas. */
    val householdSize: Int = 2,
    val children: Int = 0,
    val meals: Int = 5,
    /** Part d'un enfant par rapport à un adulte (0,5 = demi-portion). */
    val childFactor: Double = 0.5,
    val preferOrganic: Boolean = true,
    val preferences: String = "",
    /** Drive Carrefour choisi dans l'app. */
    val storeId: String = "",
    val storeName: String = "",
    /** Contexte du drive lu dans le panier Carrefour (mémorisé pour les fois suivantes). */
    val basketServiceId: String = "",
    val subBasketType: String = "",
    val facilityServiceId: String = "",
    /** Debug : WebView présentée comme un Chrome normal (sans « ; wv » dans le User-Agent). */
    val uaWithoutWv: Boolean = false,
) {
    fun driveContext(): DriveContext? = basketServiceId.takeIf { it.isNotBlank() }?.let {
        DriveContext(it, subBasketType.ifBlank { fr.mescourses.core.carrefour.CarrefourRoutes.DEFAULT_SUB_BASKET }, facilityServiceId.ifBlank { null })
    }
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val modelPath = stringPreferencesKey("model_path")
        val preferGpu = booleanPreferencesKey("prefer_gpu")
        val hfToken = stringPreferencesKey("hf_token")
        val household = intPreferencesKey("household")
        val children = intPreferencesKey("children")
        val meals = intPreferencesKey("meals")
        val childFactor = androidx.datastore.preferences.core.doublePreferencesKey("child_factor")
        val organic = booleanPreferencesKey("prefer_organic")
        val preferences = stringPreferencesKey("preferences")
        val storeId = stringPreferencesKey("cf_store_id")
        val storeName = stringPreferencesKey("cf_store_name")
        val basket = stringPreferencesKey("cf_basket_service")
        val subBasket = stringPreferencesKey("cf_sub_basket")
        val facility = stringPreferencesKey("cf_facility_service")
        val uaWithoutWv = booleanPreferencesKey("cf_ua_without_wv")
    }

    private fun Preferences.toSettings() = Settings(
        modelPath = this[K.modelPath].orEmpty(),
        preferGpu = this[K.preferGpu] ?: true,
        hfToken = this[K.hfToken].orEmpty(),
        householdSize = this[K.household] ?: 2,
        children = this[K.children] ?: 0,
        meals = this[K.meals] ?: 5,
        childFactor = this[K.childFactor] ?: 0.5,
        preferOrganic = this[K.organic] ?: true,
        preferences = this[K.preferences].orEmpty(),
        storeId = this[K.storeId].orEmpty(),
        storeName = this[K.storeName].orEmpty(),
        basketServiceId = this[K.basket].orEmpty(),
        subBasketType = this[K.subBasket].orEmpty(),
        facilityServiceId = this[K.facility].orEmpty(),
        uaWithoutWv = this[K.uaWithoutWv] ?: false,
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
            prefs[K.hfToken] = s.hfToken
            prefs[K.household] = s.householdSize
            prefs[K.children] = s.children
            prefs[K.meals] = s.meals
            prefs[K.childFactor] = s.childFactor
            prefs[K.organic] = s.preferOrganic
            prefs[K.preferences] = s.preferences
            prefs[K.storeId] = s.storeId
            prefs[K.storeName] = s.storeName
            prefs[K.basket] = s.basketServiceId
            prefs[K.subBasket] = s.subBasketType
            prefs[K.facility] = s.facilityServiceId
            prefs[K.uaWithoutWv] = s.uaWithoutWv
        }
    }
}

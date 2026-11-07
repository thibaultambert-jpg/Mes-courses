package fr.mescourses.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.mescourses.core.mcp.Retailer
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
    val householdSize: Int = 2,
    val preferences: String = "",
    val searxngUrl: String = "",
    val mcpToken: String = "",
    val retailer: Retailer = Retailer.CARREFOUR,
    val carrefourUrl: String = "",
    val leclercUrl: String = "",
    val auchanUrl: String = "",
) {
    fun mcpUrl(r: Retailer): String = when (r) {
        Retailer.CARREFOUR -> carrefourUrl
        Retailer.LECLERC -> leclercUrl
        Retailer.AUCHAN -> auchanUrl
    }
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val modelPath = stringPreferencesKey("model_path")
        val preferGpu = booleanPreferencesKey("prefer_gpu")
        val hfToken = stringPreferencesKey("hf_token")
        val household = intPreferencesKey("household")
        val preferences = stringPreferencesKey("preferences")
        val searxng = stringPreferencesKey("searxng_url")
        val mcpToken = stringPreferencesKey("mcp_token")
        val retailer = stringPreferencesKey("retailer")
        val carrefour = stringPreferencesKey("mcp_carrefour")
        val leclerc = stringPreferencesKey("mcp_leclerc")
        val auchan = stringPreferencesKey("mcp_auchan")
    }

    private fun Preferences.toSettings() = Settings(
        modelPath = this[K.modelPath].orEmpty(),
        preferGpu = this[K.preferGpu] ?: true,
        hfToken = this[K.hfToken].orEmpty(),
        householdSize = this[K.household] ?: 2,
        preferences = this[K.preferences].orEmpty(),
        searxngUrl = this[K.searxng].orEmpty(),
        mcpToken = this[K.mcpToken].orEmpty(),
        retailer = this[K.retailer]?.let { runCatching { Retailer.valueOf(it) }.getOrNull() } ?: Retailer.CARREFOUR,
        carrefourUrl = this[K.carrefour].orEmpty(),
        leclercUrl = this[K.leclerc].orEmpty(),
        auchanUrl = this[K.auchan].orEmpty(),
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
            prefs[K.preferences] = s.preferences
            prefs[K.searxng] = s.searxngUrl.trim()
            prefs[K.mcpToken] = s.mcpToken.trim()
            prefs[K.retailer] = s.retailer.name
            prefs[K.carrefour] = s.carrefourUrl.trim()
            prefs[K.leclerc] = s.leclercUrl.trim()
            prefs[K.auchan] = s.auchanUrl.trim()
        }
    }
}

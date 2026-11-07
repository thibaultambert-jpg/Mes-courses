package fr.mescourses.app

import android.content.Context
import androidx.room.Room
import fr.mescourses.app.data.AppDatabase
import fr.mescourses.app.data.MIGRATION_1_2
import fr.mescourses.app.data.MIGRATION_2_3
import fr.mescourses.app.data.RoomMealPlanRepository
import fr.mescourses.app.data.RoomMenuDraftRepository
import fr.mescourses.app.data.RoomRecipeRepository
import fr.mescourses.app.data.RoomShoppingRepository
import fr.mescourses.app.data.SettingsStore
import fr.mescourses.app.llm.LiteRtLmEngine
import fr.mescourses.app.llm.LlmEngine
import fr.mescourses.app.llm.ModelManager
import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.cart.RetailerSession
import fr.mescourses.core.menu.MenuConfig
import fr.mescourses.core.menu.MenuPrompt
import fr.mescourses.core.menu.MenuTools
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.core.model.Portions
import fr.mescourses.core.mcp.McpServerConfig
import fr.mescourses.core.mcp.Retailer
import fr.mescourses.core.mcp.SdkMcpToolClient
import fr.mescourses.core.recipes.RecipeImporter
import fr.mescourses.core.recipes.SearxngWebSearch
import java.time.LocalDate
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
/** Injection de dépendances manuelle (application personnelle, pas besoin de Hilt). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "mes-courses.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
    val recipes = RoomRecipeRepository(db.recipeDao())
    val plan = RoomMealPlanRepository(db.mealDao())
    val shopping = RoomShoppingRepository(db.shoppingDao())
    val draft = RoomMenuDraftRepository(db.menuDraftDao())

    val settings = SettingsStore(context, appScope)

    val http = HttpClient(CIO) {
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 90_000
            connectTimeoutMillis = 15_000
        }
        followRedirects = true
    }

    val webSearch = SearxngWebSearch(http) { settings.current.searxngUrl }
    val importer = RecipeImporter(http)

    val llm: LlmEngine = LiteRtLmEngine(context)
    val models = ModelManager(context, appScope)

    val retailers = RetailerConnections(http) { r ->
        val s = settings.current
        McpServerConfig(url = s.mcpUrl(r), token = s.mcpToken)
    }

    fun defaultPortions(): Portions = settings.current.let { Portions(it.householdSize, it.children) }

    suspend fun menuSystemPrompt(config: MenuConfig): String =
        MenuPrompt.system(LocalDate.now(), config, recipes.all(), settings.current.preferences)

    fun menuTools(): List<AgentTool> = MenuTools(draft, recipes, ::defaultPortions).all()

    fun menuValidator() = MenuValidator(
        generator = TextGenerator { system, prompt -> llm.generateOnce(system, prompt) },
        recipes = recipes,
        shopping = shopping,
        plan = plan,
        draft = draft,
        childFactor = { settings.current.childFactor },
    )
}

/** Une session MCP par enseigne, ouverte à la demande et réutilisée. */
class RetailerConnections(
    private val http: HttpClient,
    private val configFor: (Retailer) -> McpServerConfig,
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<Retailer, Pair<McpServerConfig, RetailerSession>>()

    suspend fun session(retailer: Retailer): RetailerSession = mutex.withLock {
        val config = configFor(retailer)
        require(config.url.isNotBlank()) { "Aucune adresse MCP configurée pour ${retailer.label} (Réglages)." }
        sessions[retailer]?.let { (cfg, s) -> if (cfg == config) return@withLock s else s.close() }
        RetailerSession.open(retailer, SdkMcpToolClient(http, config)).also { sessions[retailer] = config to it }
    }

    suspend fun reset(retailer: Retailer) = mutex.withLock {
        sessions.remove(retailer)?.second?.close()
    }
}

package fr.mescourses.app

import android.content.Context
import androidx.room.Room
import fr.mescourses.app.carrefour.CarrefourSession
import fr.mescourses.app.carrefour.CarrefourWebViewBridge
import fr.mescourses.app.carrefour.ConfirmationBroker
import fr.mescourses.app.data.AppDatabase
import fr.mescourses.app.data.MIGRATION_1_2
import fr.mescourses.app.data.MIGRATION_2_3
import fr.mescourses.app.data.MIGRATION_3_4
import fr.mescourses.app.data.RoomMealPlanRepository
import fr.mescourses.app.data.RoomMenuDraftRepository
import fr.mescourses.app.data.RoomProductMemory
import fr.mescourses.app.data.RoomRecipeRepository
import fr.mescourses.app.data.RoomShoppingRepository
import fr.mescourses.app.data.SettingsStore
import fr.mescourses.app.llm.LiteRtLmEngine
import fr.mescourses.app.llm.LlmEngine
import fr.mescourses.app.llm.ModelController
import fr.mescourses.app.llm.ModelManager
import fr.mescourses.core.agent.AgentTool
import fr.mescourses.core.carrefour.CarrefourClient
import fr.mescourses.core.carrefour.CarrefourTools
import fr.mescourses.core.carrefour.ShoppingPrompt
import fr.mescourses.core.menu.MenuConfig
import fr.mescourses.core.menu.MenuPrompt
import fr.mescourses.core.menu.MenuTools
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.RecipeStepsWriter
import fr.mescourses.core.menu.TextGenerator
import fr.mescourses.app.work.ChefWork
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.recipes.HttpPageFetcher
import fr.mescourses.core.recipes.RecipeImporter
import fr.mescourses.core.recipes.RecipeSearcher
import fr.mescourses.app.recipes.WebViewPageFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/** Articles à envoyer au panier, posés par la fiche recette ou la liste de courses. */
data class PendingCart(val label: String, val items: List<ShoppingItem>)

/** Injection de dépendances manuelle (application personnelle, pas besoin de Hilt). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "mes-courses.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
    val recipes = RoomRecipeRepository(db.recipeDao())
    val plan = RoomMealPlanRepository(db.mealDao())
    val shopping = RoomShoppingRepository(db.shoppingDao())
    val draft = RoomMenuDraftRepository(db.menuDraftDao())
    val productMemory = RoomProductMemory(db.ingredientProductDao())

    val settings = SettingsStore(context, appScope)

    /** Client HTTP classique : pour les sites de recettes (import et recherche). */
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
        followRedirects = true
    }
    /** Navigateur invisible : second recours quand un site refuse le client HTTP. */
    private val webFetcher = WebViewPageFetcher(context.applicationContext)
    private val fetchers = listOf(HttpPageFetcher(http), webFetcher)
    val importer = RecipeImporter(fetchers)
    val recipeSearch = RecipeSearcher(fetchers)

    // --- LLM local ---
    private val llm: LlmEngine = LiteRtLmEngine(context)
    val models = ModelManager(context, appScope)
    val model = ModelController(llm, settings)
    /** Travaux longs du chef (proposition, validation), qui continuent quand on quitte l'app. */
    val chef = ChefWork(context, appScope, model, recipes) { stepsWriter() }

    // --- Carrefour (natif, via une WebView connectée) ---
    val bridge = CarrefourWebViewBridge(context) { settings.current.uaWithoutWv }
    val carrefour = CarrefourClient(
        bridge,
        savedContext = { settings.current.driveContext() },
        // Drive découvert (panier ou offres d'une recherche) : mémorisé pour les fois suivantes.
        onContext = { ctx, _ ->
            appScope.launch {
                settings.update {
                    it.copy(basketServiceId = ctx.basketServiceId, subBasketType = ctx.subBasketType, facilityServiceId = ctx.facilityServiceId ?: it.facilityServiceId)
                }
            }
        },
    )
    val session = CarrefourSession(carrefour, bridge, settings)
    val confirmations = ConfirmationBroker()
    val pendingCart = MutableStateFlow<PendingCart?>(null)

    fun defaultPortions(): Portions = settings.current.let { Portions(it.householdSize, it.children) }

    suspend fun menuSystemPrompt(config: MenuConfig): String =
        MenuPrompt.system(LocalDate.now(), config, recipes.all(), settings.current.preferences)

    fun menuTools(): List<AgentTool> = MenuTools(draft, recipes, ::defaultPortions).all()

    fun shoppingSystemPrompt(): String = ShoppingPrompt.system(settings.current.preferOrganic, settings.current.preferences)

    fun shoppingTools(): List<AgentTool> = CarrefourTools(carrefour, confirmations).all()

    fun stepsWriter() = RecipeStepsWriter(TextGenerator { system, prompt -> model.generateOnce(system, prompt) })

    fun menuValidator() = MenuValidator(
        generator = TextGenerator { system, prompt -> model.generateOnce(system, prompt) },
        recipes = recipes,
        shopping = shopping,
        plan = plan,
        draft = draft,
        childFactor = { settings.current.childFactor },
    )
}

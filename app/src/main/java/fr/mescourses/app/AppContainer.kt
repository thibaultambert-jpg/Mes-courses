package fr.mescourses.app

import android.content.Context
import androidx.room.Room
import fr.mescourses.app.retail.AuchanTokens
import fr.mescourses.app.retail.ConfirmationBroker
import fr.mescourses.app.retail.RetailerSession
import fr.mescourses.app.retail.RetailerSites
import fr.mescourses.app.retail.RetailerWebViewBridge
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
import fr.mescourses.core.auchan.AuchanClient
import fr.mescourses.core.cart.CartLine
import fr.mescourses.core.cart.ProductMemory
import fr.mescourses.core.cart.RetailerBackend
import fr.mescourses.core.carrefour.CarrefourClient
import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.core.carrefour.CarrefourTools
import fr.mescourses.core.leclerc.LeclercClient
import fr.mescourses.core.retail.Retailer
import fr.mescourses.core.retail.RetailerProductMemory
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
import android.webkit.CookieManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** Articles à envoyer au panier, posés par la fiche recette ou la liste de courses. */
data class PendingCart(val label: String, val items: List<ShoppingItem>)

/** Panier proposé par la comparaison des prix, à reprendre tel quel dans l'écran Panier. */
data class PendingQuote(val label: String, val retailer: Retailer, val lines: List<CartLine>)

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
    private val roomProductMemory = RoomProductMemory(db.ingredientProductDao())
    /** Mémoire des choix de l'enseigne donnée (les identifiants produit diffèrent d'une enseigne à l'autre). */
    fun productMemory(r: Retailer = activeRetailer.value): ProductMemory = RetailerProductMemory(r, roomProductMemory)

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

    /**
     * Sélectionne un modèle au niveau de l'application : le choix est enregistré même si l'écran des
     * Réglages a été quitté pendant le téléchargement.
     */
    fun selectModel(path: String) {
        appScope.launch {
            settings.update { it.copy(modelPath = path) }
            model.refreshAvailability()
        }
    }

    init {
        // Aucun modèle choisi (ou fichier supprimé) mais un seul installé : on le prend d'office.
        appScope.launch {
            settings.update { s ->
                val chosen = s.modelPath.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                if (chosen != null) s else models.installed().singleOrNull()?.let { s.copy(modelPath = it.absolutePath) } ?: s
            }
            model.refreshAvailability()
        }
    }
    /** Travaux longs du chef (proposition, validation), qui continuent quand on quitte l'app. */
    val chef = ChefWork(context, appScope, model, recipes) { stepsWriter() }

    // --- Drives (natifs, via une WebView connectée par enseigne) ---
    private val sites = RetailerSites.create { settings.current }
    val auchanTokens = AuchanTokens()
    val bridges: Map<Retailer, RetailerWebViewBridge> = Retailer.entries.associateWith { r ->
        RetailerWebViewBridge(
            context,
            sites.getValue(r),
            uaWithoutWv = { settings.current.uaWithoutWv },
            leclercChrome = { settings.current.leclercChromeUa },
            onRequestSeen = if (r == Retailer.AUCHAN) auchanTokens::seen else null,
        )
    }

    val carrefour = CarrefourClient(
        bridges.getValue(Retailer.CARREFOUR),
        savedContext = { settings.current.driveContext() },
        storeRef = { settings.current.storeRef.ifBlank { null } },
        // Drive découvert (panier ou offres d'une recherche) : mémorisé pour les fois suivantes.
        onContext = { ctx, _ ->
            appScope.launch {
                settings.update {
                    it.copy(basketServiceId = ctx.basketServiceId, subBasketType = ctx.subBasketType, facilityServiceId = ctx.facilityServiceId ?: it.facilityServiceId)
                }
            }
        },
    )
    val leclerc = LeclercClient(bridges.getValue(Retailer.LECLERC), store = { settings.current.leclercStore() })
    val auchan = AuchanClient(
        bridges.getValue(Retailer.AUCHAN),
        auth = { auchanTokens.current() },
        // La page panier rappelle l'API du site : on y relève des jetons frais (au plus ~4 s après le chargement).
        refreshAuth = {
            auchanTokens.clear()
            bridges.getValue(Retailer.AUCHAN).reloadAndWait()
            var tries = 0
            while (auchanTokens.current()?.complete != true && tries++ < 8) delay(500)
        },
        onStore = { name ->
            if (name != settings.current.auchanStoreName) appScope.launch { settings.update { it.copy(auchanStoreName = name) } }
        },
    )
    val backends: Map<Retailer, RetailerBackend> = mapOf(Retailer.CARREFOUR to carrefour, Retailer.LECLERC to leclerc, Retailer.AUCHAN to auchan)

    val sessions: Map<Retailer, RetailerSession> = Retailer.entries.associateWith { r ->
        val bridge = bridges.getValue(r)
        if (r == Retailer.CARREFOUR) {
            RetailerSession(
                r, carrefour, bridge,
                // Renouvellement depuis le cookie SSO : retour sur www.carrefour.fr = renouvelé, rester sur moncompte = reconnexion.
                refresher = {
                    bridge.visit(CarrefourRoutes.AUTHORIZE_URL) { it.startsWith(CarrefourRoutes.ORIGIN) || it.startsWith(CarrefourRoutes.IAM_ORIGIN) }
                        ?.startsWith(CarrefourRoutes.ORIGIN) == true
                },
                ssoCookie = { CookieManager.getInstance().getCookie(CarrefourRoutes.IAM_ORIGIN)?.contains("c4iamsecuretk=") == true },
            )
        } else {
            RetailerSession(r, backends.getValue(r), bridge)
        }
    }

    /** Enseigne du panier en cours : la préférée par défaut, changeable à la demande (sans toucher aux Réglages). */
    val activeRetailer = MutableStateFlow(Retailer.CARREFOUR)
    /** Enseignes interrogées par une comparaison des prix en cours. */
    val comparing = MutableStateFlow<Set<Retailer>>(emptySet())
    /** Enseignes dont la WebView invisible doit être attachée à l'activité. */
    /** Enseigne testée dans l'écran de debug (sa WebView doit être chargée). */
    val debugRetailer = MutableStateFlow<Retailer?>(null)
    val attachedRetailers: StateFlow<Set<Retailer>> = combine(activeRetailer, comparing, debugRetailer) { a, c, d -> c + a + setOfNotNull(d) }
        .stateIn(appScope, SharingStarted.Eagerly, setOf(Retailer.CARREFOUR))

    init {
        // L'enseigne préférée des Réglages devient l'enseigne active dès qu'elle change (et au démarrage).
        appScope.launch {
            settings.state.map { it.preferredRetailer }.distinctUntilChanged().collect { activeRetailer.value = it }
        }
    }

    fun backend(r: Retailer = activeRetailer.value): RetailerBackend = backends.getValue(r)
    fun session(r: Retailer = activeRetailer.value): RetailerSession = sessions.getValue(r)
    fun bridge(r: Retailer = activeRetailer.value): RetailerWebViewBridge = bridges.getValue(r)

    /** Les cookies sont communs à toutes les enseignes : la déconnexion vaut pour toutes. */
    fun logoutAll() {
        RetailerSession.clearAllCookies()
        sessions.values.forEach { it.markLoggedOut() }
    }

    val confirmations = ConfirmationBroker()
    val pendingCart = MutableStateFlow<PendingCart?>(null)
    /** Articles à comparer (posés par l'écran Panier) ; null = liste de courses. */
    val pendingCompare = MutableStateFlow<PendingCart?>(null)
    val pendingQuote = MutableStateFlow<PendingQuote?>(null)

    fun defaultPortions(): Portions = settings.current.let { Portions(it.householdSize, it.children) }

    suspend fun menuSystemPrompt(config: MenuConfig): String =
        MenuPrompt.system(LocalDate.now(), config, recipes.all(), settings.current.preferences)

    fun menuTools(): List<AgentTool> = MenuTools(draft, recipes, ::defaultPortions).all()

    fun shoppingSystemPrompt(): String = ShoppingPrompt.system(settings.current.preferOrganic, settings.current.preferences, activeRetailer.value)

    fun shoppingTools(): List<AgentTool> = CarrefourTools(backend(), confirmations).all()

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

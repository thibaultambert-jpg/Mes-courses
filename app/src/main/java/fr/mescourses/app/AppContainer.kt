package fr.mescourses.app

import android.content.Context
import androidx.room.Room
import fr.mescourses.app.retail.AuchanTokens
import fr.mescourses.app.retail.ConfirmationBroker
import fr.mescourses.app.retail.RetailerSession
import fr.mescourses.app.retail.RetailerSites
import fr.mescourses.app.retail.RetailerWebViewBridge
import fr.mescourses.app.data.AppDatabase
import fr.mescourses.app.data.CatalogDatabase
import fr.mescourses.app.data.CatalogSync
import fr.mescourses.app.data.RoomCatalogStore
import fr.mescourses.app.data.MIGRATION_1_2
import fr.mescourses.app.data.MIGRATION_2_3
import fr.mescourses.app.data.MIGRATION_3_4
import fr.mescourses.app.data.MIGRATION_4_5
import fr.mescourses.app.data.MIGRATION_5_6
import fr.mescourses.app.data.RoomMealPlanRepository
import fr.mescourses.app.data.RoomMenuDraftRepository
import fr.mescourses.app.data.RoomMenuHistoryRepository
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
import fr.mescourses.core.menu.CandidateFinder
import fr.mescourses.core.menu.CandidatePool
import fr.mescourses.core.menu.MenuHistory
import fr.mescourses.core.menu.MenuWishes
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
import kotlinx.coroutines.flow.first
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

    /** Pop-in « Annuler » partagées par tous les écrans. */
    val undoBar = fr.mescourses.app.ui.common.UndoBar(appScope)

    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "mes-courses.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .build()
    val recipes = RoomRecipeRepository(db.recipeDao())
    val plan = RoomMealPlanRepository(db.mealDao())
    val shopping = RoomShoppingRepository(db.shoppingDao())
    val draft = RoomMenuDraftRepository(db.menuDraftDao())
    val menuHistory = RoomMenuHistoryRepository(db.menuHistoryDao())
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

    /** Catalogue local de recettes : base à part, reconstruite si son format change (elle se recharge toute seule). */
    val catalog = RoomCatalogStore(
        Room.databaseBuilder(context, CatalogDatabase::class.java, "catalogue.db").fallbackToDestructiveMigration(true).build().dao(),
    )
    val catalogSync = CatalogSync(context.applicationContext, catalog, appScope, http).also { it.start() }
    /** Mise à jour de l'appli elle-même (release « appli » du dépôt). */
    val appUpdater = fr.mescourses.app.data.AppUpdater(context.applicationContext, appScope, http).also { it.start() }

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
    val chef = ChefWork(context, appScope, model, recipes, { stepsWriter() }, { url -> runCatching { importer.import(url) }.getOrNull() })

    // --- Drives (natifs, via une WebView connectée par enseigne) ---
    private val sites = RetailerSites.create { settings.current }
    val auchanTokens = AuchanTokens()
    val bridges: Map<Retailer, RetailerWebViewBridge> = Retailer.entries.associateWith { r ->
        RetailerWebViewBridge(
            context,
            sites.getValue(r),
            uaWithoutWv = { settings.current.uaWithoutWv },
            leclercUaMode = { settings.current.leclercUaMode },
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
    val leclerc = LeclercClient(
        bridges.getValue(Retailer.LECLERC),
        store = { settings.current.leclercStore() },
        // La session Leclerc est liée au drive ouvert dans le navigateur : on y ouvre la page du drive choisi.
        rebind = { bridges.getValue(Retailer.LECLERC).reloadAndWait() },
        // Aucun drive choisi : on reprend celui du compte, lu sur la page d'accueil connectée.
        onStoreFound = { s ->
            settings.update {
                if (it.leclercStoreId.isNotBlank()) it
                else it.copy(leclercStoreId = s.id, leclercNoPR = s.noPR, leclercHost = s.host, leclercStoreName = s.name, leclercPath = s.path)
            }
        },
    )
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
    /** Enseignes vérifiées par l'écran des Réglages (leurs WebViews doivent être chargées pour répondre). */
    val checkingRetailers = MutableStateFlow<Set<Retailer>>(emptySet())
    val attachedRetailers: StateFlow<Set<Retailer>> =
        combine(activeRetailer, comparing, debugRetailer, checkingRetailers) { a, c, d, k -> c + a + setOfNotNull(d) + k }
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

    /**
     * Personnes choisies sur une carte (menu ou semaine) au moment d'ouvrir la recette : l'écran détail les reprend, et
     * ce qu'on y change revient sur la carte. Clé : « id:N » (recette du livre) ou « url:… » (fiche d'un site).
     */
    private val portionHints = java.util.concurrent.ConcurrentHashMap<String, Portions>()

    private fun hintKey(recipeId: Long?, url: String?): String? =
        recipeId?.takeIf { it != 0L }?.let { "id:$it" } ?: url?.let { "url:" + fr.mescourses.core.recipes.RecipeSites.normalize(it) }

    fun hintPortions(recipeId: Long?, url: String?, portions: Portions) {
        hintKey(recipeId, null)?.let { portionHints[it] = portions }
        hintKey(null, url)?.let { portionHints[it] = portions }
    }

    /** Personnes de la carte d'où l'on vient (une seule fois) ; null = ouverte ailleurs, on prend les Réglages. */
    fun takePortionHint(recipeId: Long?, url: String?): Portions? =
        hintKey(recipeId, url)?.let { portionHints.remove(it) }.also { hintKey(recipeId, null)?.let { k -> portionHints.remove(k) }; hintKey(null, url)?.let { k -> portionHints.remove(k) } }

    /** Reporte sur les cartes du menu en cours et de la semaine le nombre de personnes choisi dans l'écran détail. */
    suspend fun syncPortions(recipeId: Long?, url: String?, portions: Portions) {
        val key = url?.let { fr.mescourses.core.recipes.RecipeSites.normalize(it) }
        val dishes = draft.get()
        if (dishes.any { d -> (recipeId != null && recipeId != 0L && d.recipeId == recipeId) || (key != null && d.sourceUrl?.let(fr.mescourses.core.recipes.RecipeSites::normalize) == key) }) {
            draft.set(dishes.map { d ->
                if ((recipeId != null && recipeId != 0L && d.recipeId == recipeId) || (key != null && d.sourceUrl?.let(fr.mescourses.core.recipes.RecipeSites::normalize) == key)) d.copy(portions = portions) else d
            })
        }
        if (recipeId != null && recipeId != 0L) {
            plan.observeAll().first().filter { it.recipeId == recipeId && !it.cooked && it.portions != portions }.forEach { plan.update(it.copy(portions = portions)) }
        }
    }

    /** Recettes trouvées sur les sites pour le menu en cours ; vide = le chef invente les plats (hors ligne). */
    val candidates = CandidatePool()

    /** Ce qu'on sait déjà d'une recette d'un site sans aller sur le site : recherche du menu, sinon fiche du catalogue. */
    suspend fun knownRecipe(url: String): fr.mescourses.core.model.Recipe? =
        candidates.byUrl(url)?.recipe ?: catalog.byUrl(url)?.toRecipe()

    private val appContext = context.applicationContext

    fun online() = fr.mescourses.app.data.isOnline(appContext)
    /** Envies du menu en cours, pour le bilan que les outils renvoient au modèle. */
    @Volatile var currentWishes: MenuWishes? = null

    val candidateFinder = CandidateFinder(recipeSearch, importer, recipes, menuHistory, pantry = { settings.current.pantryList() }, catalog = catalog)

    /** Prompt du menu : choix parmi les candidates des sites, ou invention des plats s'il n'y en a pas. */
    suspend fun menuSystemPrompt(wishes: MenuWishes, adjusting: Boolean = false): String {
        val today = LocalDate.now()
        val cookbook = recipes.all()
        val pool = candidates.all()
        return if (pool.isEmpty()) MenuPrompt.system(today, wishes, cookbook, settings.current.preferences)
        else MenuPrompt.systemWeb(today, wishes, pool, MenuHistory(menuHistory.all(), cookbook, today), settings.current.preferences, adjusting)
    }

    fun menuTools(adjusting: Boolean = false, maxChanges: Int? = null): List<AgentTool> = MenuTools(
        draft, recipes, ::defaultPortions, candidates,
        wishes = { currentWishes },
        search = { words -> candidateFinder.search(words, currentWishes ?: MenuWishes(settings.current.meals, defaultPortions()), limit = 4) },
        maxChanges = maxChanges,
    ).all(adjusting)

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
        pantry = { settings.current.pantryList() },
        history = menuHistory,
        // Recette complète déjà lue pendant la recherche ; sinon on lit la page pour ses étapes. Sans réseau, la
        // fiche du catalogue suffit pour la liste de courses : les étapes seront écrites à l'ouverture de la recette.
        webRecipe = { url ->
            val known = candidates.byUrl(url)?.recipe
            known?.takeIf { it.steps.isNotEmpty() }
                ?: runCatching { importer.import(url) }.getOrNull()
                ?: (known ?: catalog.byUrl(url)?.toRecipe())?.let { it.copy(tags = it.tags + MenuValidator.STEPS_PENDING) }
        },
    )
}

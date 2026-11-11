package fr.mescourses.app.work

import android.content.Context
import fr.mescourses.app.llm.ModelController
import fr.mescourses.core.menu.MenuValidator
import fr.mescourses.core.menu.RecipeStepsWriter
import fr.mescourses.core.repository.RecipeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import fr.mescourses.core.menu.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Avancement de la validation du menu. */
data class Validation(val done: Int, val total: Int, val current: String)

/**
 * Travaux longs du chef, à l'échelle de l'application (pas de l'écran) : ils continuent quand on quitte l'app.
 * Pendant tout le travail, le modèle est réservé (onTrimMemory ne le libère pas) et un service au premier plan tourne.
 */
class ChefWork(
    private val context: Context,
    private val scope: CoroutineScope,
    private val model: ModelController,
    private val recipes: RecipeRepository,
    private val stepsWriter: () -> RecipeStepsWriter,
    /** Recette lue sur la page d'origine (null si le site ne répond pas ou n'a pas de fiche lisible). */
    private val siteRecipe: suspend (url: String) -> fr.mescourses.core.model.Recipe? = { null },
) {
    private val running = AtomicInteger(0)

    private companion object {
        const val NO_MODEL = "L'assistant n'est pas installé : installe-le dans les Réglages."
    }

    private val _validation = MutableStateFlow<Validation?>(null)
    val validation: StateFlow<Validation?> = _validation

    /** Résultat de la dernière validation, à afficher une fois ([consumeResult]). */
    private val _result = MutableStateFlow<Result<ValidationResult>?>(null)
    val result: StateFlow<Result<ValidationResult>?> = _result

    fun consumeResult() { _result.value = null }

    private var validationJob: Job? = null

    /** Exécute [block] en gardant l'application et le modèle éveillés. */
    suspend fun <T> keepAwake(text: String, block: suspend () -> T): T {
        if (running.getAndIncrement() == 0) MenuWorkService.start(context, text) else MenuWorkService.update(context, text)
        return try {
            model.use { block() }
        } finally {
            if (running.decrementAndGet() == 0) MenuWorkService.stop(context)
        }
    }

    fun progress(text: String) = MenuWorkService.update(context, text)

    val validating: Boolean get() = validationJob?.isActive == true

    fun validate(validator: MenuValidator, total: Int) {
        if (validating) return
        _validation.value = Validation(0, total, "")
        _result.value = null
        validationJob = scope.launch {
            val result = runCatching {
                keepAwake("Le chef prépare la liste de courses…") {
                    validator.validate { done, all, current ->
                        _validation.value = Validation(done, all, current)
                        if (current.isNotEmpty()) progress("Ingrédients ${done + 1}/$all : $current")
                    }
                }
            }
            _validation.value = null
            _result.value = result
            // Les ingrédients sont prêts : le chef écrit maintenant les étapes, une recette après l'autre.
            if (result.isSuccess) enqueuePendingSteps()
        }
    }

    // ---------- Étapes des recettes, en file (jamais en parallèle : un seul modèle) ----------

    /** Recettes en attente d'étapes, dans l'ordre de traitement. */
    private val _stepsQueue = MutableStateFlow<List<Long>>(emptyList())
    val stepsQueue: StateFlow<List<Long>> = _stepsQueue

    /** Recette dont les étapes sont en cours d'écriture. */
    private val _writing = MutableStateFlow<Long?>(null)
    val writing: StateFlow<Long?> = _writing

    /** Recette dont les étapes sont recherchées sur le site d'origine. */
    private val _fetching = MutableStateFlow<Long?>(null)
    val fetching: StateFlow<Long?> = _fetching

    /** Échecs (recette → message), pour proposer « Réessayer ». */
    private val _stepsFailures = MutableStateFlow<Map<Long, String>>(emptyMap())
    val stepsFailures: StateFlow<Map<Long, String>> = _stepsFailures

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wakeUp) {
                while (true) {
                    val id = _stepsQueue.value.firstOrNull() ?: break
                    runCatching { process(id) }
                    _stepsQueue.update { it - id }
                }
            }
        }
    }

    /**
     * Étapes d'une recette : d'abord celles du site d'origine (si on a internet), sinon le chef les écrit.
     * Sans assistant installé, on ne démarre surtout pas le service au premier plan, qu'on arrêterait aussitôt
     * (Android plante si startForeground() n'est pas appelé à temps).
     */
    private suspend fun process(id: Long) {
        val recipe = recipes.get(id) ?: return
        if (!stepsWriter().needsSteps(recipe)) return
        if (fromSite(recipe)) return
        if (!model.status.value.available) {
            _stepsFailures.update { it + (id to NO_MODEL) }
            return
        }
        keepAwake("Le chef écrit les recettes…") { writeOne(id) }
    }

    /** Tente de lire les étapes sur la page d'origine. Renvoie true si la recette est complétée. */
    private suspend fun fromSite(recipe: fr.mescourses.core.model.Recipe): Boolean {
        val url = recipe.sourceUrl ?: return false
        if (!online()) return false
        _fetching.value = recipe.id
        try {
            val web = siteRecipe(url)?.takeIf { it.steps.isNotEmpty() } ?: return false
            // Relit la recette : elle a pu être modifiée entre-temps.
            val current = recipes.get(recipe.id) ?: return true
            recipes.save(
                current.copy(
                    steps = web.steps,
                    prepMinutes = current.prepMinutes ?: web.prepMinutes,
                    cookMinutes = current.cookMinutes ?: web.cookMinutes,
                    tags = current.tags - MenuValidator.STEPS_PENDING,
                ),
            )
            _stepsFailures.update { it - recipe.id }
            return true
        } finally {
            _fetching.value = null
        }
    }

    private fun online() = fr.mescourses.app.data.isOnline(context)

    /**
     * À l'ouverture d'une recette : relit seulement la page du site (si on a internet), sans jamais lancer le chef.
     * Si le site ne donne rien, c'est à l'utilisateur de demander au chef de compléter.
     */
    fun checkSite(id: Long) {
        scope.launch {
            val recipe = recipes.get(id) ?: return@launch
            if (!stepsWriter().needsSteps(recipe) || id in _stepsQueue.value || _writing.value == id) return@launch
            runCatching { fromSite(recipe) }
        }
    }

    private suspend fun writeOne(id: Long) {
        val recipe = recipes.get(id) ?: return
        val writer = stepsWriter()
        if (!writer.needsSteps(recipe)) return
        if (!model.status.value.available) {
            _stepsFailures.update { it + (id to NO_MODEL) }
            return
        }
        _writing.value = id
        progress("Le chef écrit « ${recipe.title} »… (${_stepsQueue.value.size} en attente)")
        try {
            val done = writer.complete(recipe)
            if (done == null) {
                _stepsFailures.update { it + (id to "Le chef n'a pas réussi à écrire les étapes.") }
            } else {
                // Relit la recette : elle a pu être modifiée entre-temps.
                val current = recipes.get(id) ?: return
                recipes.save(current.copy(steps = done.steps, prepMinutes = done.prepMinutes, cookMinutes = done.cookMinutes, notes = done.notes, tags = done.tags))
                _stepsFailures.update { it - id }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _stepsFailures.update { it + (id to (e.message ?: e.toString())) }
        } finally {
            _writing.value = null
        }
    }

    /** Ajoute une recette à la file ; [first] = l'utilisateur l'a ouverte, elle passe devant. */
    fun requestSteps(id: Long, first: Boolean = false) {
        _stepsFailures.update { it - id }
        _stepsQueue.update { q ->
            when {
                _writing.value == id -> q
                first -> listOf(id) + (q - id)
                id in q -> q
                else -> q + id
            }
        }
        wakeUp.trySend(Unit)
    }

    /** Met en file toutes les recettes du livre qui attendent leurs étapes. */
    fun enqueuePendingSteps() {
        scope.launch {
            val writer = stepsWriter()
            recipes.all().filter { writer.needsSteps(it) }.forEach { requestSteps(it.id) }
        }
    }

    fun cancelValidation() {
        validationJob?.cancel()
        _validation.value = null
    }
}

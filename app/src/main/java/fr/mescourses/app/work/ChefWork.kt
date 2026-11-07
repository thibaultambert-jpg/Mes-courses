package fr.mescourses.app.work

import android.content.Context
import fr.mescourses.app.llm.ModelController
import fr.mescourses.core.menu.MenuValidator
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
) {
    private val running = AtomicInteger(0)

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
        }
    }

    fun cancelValidation() {
        validationJob?.cancel()
        _validation.value = null
    }
}

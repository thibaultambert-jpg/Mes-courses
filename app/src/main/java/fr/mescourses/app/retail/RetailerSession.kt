package fr.mescourses.app.retail

import android.webkit.CookieManager
import fr.mescourses.core.cart.RetailerBackend
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.WriteConfirmer
import fr.mescourses.core.carrefour.WriteRequest
import fr.mescourses.core.retail.Retailer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class SessionState(val label: String) {
    INCONNU("Vérification…"),
    CONNECTE("Connecté"),
    CONNEXION_REQUISE("Connexion requise"),
    VERIFICATION("Vérification anti-robot requise"),
    DRIVE_REQUIS("Drive à choisir"),
}

/**
 * Session d'une enseigne : connexion manuelle (WebView visible), renouvellement silencieux quand l'enseigne
 * le permet ([refresher]), état observable.
 */
class RetailerSession(
    val retailer: Retailer,
    private val backend: RetailerBackend,
    private val bridge: RetailerWebViewBridge,
    /** Renouvellement sans saisie (Carrefour : cookie SSO) ; true = session renouvelée. */
    private val refresher: (suspend () -> Boolean)? = null,
    /** Présence d'un cookie de connexion longue durée (jamais lu ni journalisé). */
    private val ssoCookie: (() -> Boolean)? = null,
) {
    private val _state = MutableStateFlow(SessionState.INCONNU)
    val state: StateFlow<SessionState> = _state

    /** Raison du dernier échec de vérification, affichée à l'utilisateur (jamais de donnée personnelle). */
    @Volatile var lastProblem: String? = null
        private set

    private val who = retailer.label

    suspend fun check(): SessionState {
        _state.value = try {
            backend.sessionProbe()
            lastProblem = null
            SessionState.CONNECTE
        } catch (e: CarrefourException.SessionExpired) {
            lastProblem = "$who répond « non connecté »."
            SessionState.CONNEXION_REQUISE
        } catch (e: CarrefourException.ChallengeRequired) {
            lastProblem = "$who demande une vérification anti-robot."
            SessionState.VERIFICATION
        } catch (e: CarrefourException.NoDrive) {
            lastProblem = e.message
            SessionState.DRIVE_REQUIS
        } catch (e: CarrefourException.Http) {
            // Route de test déplacée (404…) : chez Carrefour, on se fie au cookie de connexion ; ailleurs, état inchangé.
            lastProblem = "Vérification indisponible (HTTP ${e.status})."
            when {
                ssoCookie == null -> _state.value
                hasSsoCookie() -> SessionState.CONNECTE
                else -> SessionState.CONNEXION_REQUISE
            }
        } catch (e: Exception) {
            lastProblem = e.message ?: e.toString()
            _state.value
        }
        return _state.value
    }

    /** La WebView visible a constaté elle-même la connexion (route sécurisée = 200) : on le croit tout de suite. */
    fun markConnected() {
        lastProblem = null
        _state.value = SessionState.CONNECTE
    }

    /** Code HTTP du test de connexion fait dans la page visible : 200, ou route déplacée (404/410) avec cookie SSO. */
    fun isLoggedInStatus(status: Int): Boolean = status == 200 || (status in setOf(404, 410) && hasSsoCookie())

    fun hasSsoCookie(): Boolean = ssoCookie?.invoke() == true

    /** Vérifie la session, tente un renouvellement silencieux, sinon demande la connexion. */
    suspend fun ensureSession(): Boolean {
        if (check() == SessionState.CONNECTE) return true
        if (_state.value == SessionState.VERIFICATION || _state.value == SessionState.DRIVE_REQUIS) return false
        val refresh = refresher
        if (refresh != null && refresh() && check() == SessionState.CONNECTE) return true
        if (_state.value == SessionState.INCONNU) _state.value = SessionState.CONNEXION_REQUISE
        return false
    }

    /** Message à afficher quand la session n'est pas prête. */
    fun problem(): String = when (_state.value) {
        SessionState.DRIVE_REQUIS -> "Choisis ton drive $who."
        SessionState.VERIFICATION -> "$who demande une vérification anti-robot : ouvre le site."
        else -> "Connecte-toi à $who."
    } + (lastProblem?.takeIf { _state.value != SessionState.DRIVE_REQUIS }?.let { " ($it)" } ?: "")

    /** Erreur remontée pendant un appel : met à jour l'état pour que l'interface propose la connexion. */
    fun onError(e: Throwable) {
        when (e) {
            is CarrefourException.SessionExpired -> _state.value = SessionState.CONNEXION_REQUISE
            is CarrefourException.ChallengeRequired -> _state.value = SessionState.VERIFICATION
            is CarrefourException.NoDrive -> _state.value = SessionState.DRIVE_REQUIS
        }
    }

    /** Après une connexion ou une visite dans la WebView visible. */
    suspend fun onVisibleSiteClosed() {
        CookieManager.getInstance().flush()
        bridge.reloadAndWait()
        // Une erreur réseau (WebView invisible pas prête) laisse l'état inchangé : une connexion déjà constatée
        // dans la WebView visible reste valable.
        check()
    }

    /** Les cookies sont communs à toute l'application : se déconnecter vaut pour toutes les enseignes. */
    fun markLoggedOut() {
        _state.value = SessionState.CONNEXION_REQUISE
    }

    companion object {
        fun clearAllCookies() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}

/** Fait confirmer dans l'interface chaque écriture demandée par Gemma (carte Confirmer / Annuler). */
class ConfirmationBroker : WriteConfirmer {
    data class Pending(val request: WriteRequest, val answer: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    override suspend fun confirm(request: WriteRequest): Boolean {
        val p = Pending(request, CompletableDeferred())
        _pending.value = p
        return try {
            withTimeoutOrNull(5 * 60 * 1000L) { p.answer.await() } ?: false
        } finally {
            _pending.compareAndSet(p, null)
        }
    }

    fun answer(ok: Boolean) {
        _pending.value?.answer?.complete(ok)
    }
}

package fr.mescourses.app.carrefour

import android.webkit.CookieManager
import fr.mescourses.app.data.SettingsStore
import fr.mescourses.core.carrefour.CarrefourClient
import fr.mescourses.core.carrefour.CarrefourException
import fr.mescourses.core.carrefour.CarrefourRoutes
import fr.mescourses.core.carrefour.WriteConfirmer
import fr.mescourses.core.carrefour.WriteRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class SessionState(val label: String) {
    INCONNU("Vérification…"),
    CONNECTE("Connecté"),
    CONNEXION_REQUISE("Connexion requise"),
    VERIFICATION("Vérification anti-robot requise"),
}

/** Session Carrefour : connexion manuelle (WebView visible), renouvellement silencieux, état observable. */
class CarrefourSession(
    private val client: CarrefourClient,
    private val bridge: CarrefourWebViewBridge,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow(SessionState.INCONNU)
    val state: StateFlow<SessionState> = _state

    /** Raison du dernier échec de vérification, affichée à l'utilisateur (jamais de donnée personnelle). */
    @Volatile var lastProblem: String? = null
        private set

    suspend fun check(): SessionState {
        _state.value = try {
            val cart = client.sessionProbe()
            lastProblem = null
            cart?.context?.let { ctx ->
                // Mémorise le contexte du drive pour les fois suivantes.
                settings.update {
                    it.copy(basketServiceId = ctx.basketServiceId, subBasketType = ctx.subBasketType, facilityServiceId = ctx.facilityServiceId ?: it.facilityServiceId)
                }
            }
            SessionState.CONNECTE
        } catch (e: CarrefourException.SessionExpired) {
            lastProblem = "Carrefour répond « non connecté » (401/403)."
            SessionState.CONNEXION_REQUISE
        } catch (e: CarrefourException.ChallengeRequired) {
            lastProblem = "Carrefour demande une vérification anti-robot."
            SessionState.VERIFICATION
        } catch (e: CarrefourException.Http) {
            // Route de test déplacée par Carrefour (404…) : on se fie au cookie de connexion.
            lastProblem = "Vérification indisponible (HTTP ${e.status})."
            if (hasSsoCookie()) SessionState.CONNECTE else SessionState.CONNEXION_REQUISE
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

    /** Présence du cookie SSO Carrefour (jamais lu ni journalisé, on teste seulement son existence). */
    fun isLoggedInStatus(status: Int): Boolean = status == 200 || (status in setOf(404, 410) && hasSsoCookie())

    fun hasSsoCookie(): Boolean =
        CookieManager.getInstance().getCookie(CarrefourRoutes.IAM_ORIGIN)?.contains("c4iamsecuretk=") == true

    /** Vérifie la session, tente un renouvellement silencieux, sinon demande la connexion. */
    suspend fun ensureSession(): Boolean {
        if (check() == SessionState.CONNECTE) return true
        if (_state.value == SessionState.VERIFICATION) return false
        if (bridge.refreshSession() && check() == SessionState.CONNECTE) return true
        if (_state.value != SessionState.VERIFICATION) _state.value = SessionState.CONNEXION_REQUISE
        return false
    }

    /** Erreur remontée pendant un appel : met à jour l'état pour que l'interface propose la connexion. */
    fun onError(e: Throwable) {
        when (e) {
            is CarrefourException.SessionExpired -> _state.value = SessionState.CONNEXION_REQUISE
            is CarrefourException.ChallengeRequired -> _state.value = SessionState.VERIFICATION
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

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _state.value = SessionState.CONNEXION_REQUISE
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

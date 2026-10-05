package com.niqob.app.feature.child.enforcement

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.data.local.enforcement.DefaultParentOverrideSessionManager
import com.niqob.app.data.local.enforcement.InMemoryParentOverrideSessionStore
import com.niqob.app.data.local.enforcement.LocalParentOverrideAuthenticator
import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.OverrideAttemptResult
import com.niqob.app.domain.enforcement.ParentOverrideAuthenticator
import com.niqob.app.domain.enforcement.ParentOverrideSessionManager
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementDecision
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class ParentOverrideUiState {
    data object Idle : ParentOverrideUiState()
    data object Authenticating : ParentOverrideUiState()
    data object Success : ParentOverrideUiState()
    data class Error(val message: String) : ParentOverrideUiState()
    data class Cooldown(val remainingSeconds: Int, val message: String) : ParentOverrideUiState()
    data class Lockout(val remainingMinutes: Int, val message: String) : ParentOverrideUiState()
}

class ScreenTimeLimitReachedViewModel(
    private val enforcementRepository: ScreenTimeEnforcementRepository = LocalScreenTimeEnforcementRepository.getInstance(),
    private val appPolicyRepository: AppEnforcementPolicyRepository = LocalAppEnforcementPolicyRepository.getInstance(),
    private val parentOverrideAuthenticator: ParentOverrideAuthenticator = LocalParentOverrideAuthenticator(),
    private val sessionManager: ParentOverrideSessionManager = DefaultParentOverrideSessionManager(
        authenticator = parentOverrideAuthenticator,
        sessionStore = InMemoryParentOverrideSessionStore(),
        enforcementRepository = enforcementRepository
    )
) : ViewModel() {

    val enforcementState: StateFlow<ScreenTimeEnforcementState?> = enforcementRepository.getEnforcementState()
    val policy: StateFlow<AppEnforcementPolicy> = appPolicyRepository.getPolicy()

    private val _parentOverrideState = MutableStateFlow<ParentOverrideUiState>(ParentOverrideUiState.Idle)
    val parentOverrideState: StateFlow<ParentOverrideUiState> = _parentOverrideState.asStateFlow()

    private val _remainingOverrideMinutes = MutableStateFlow<Int>(0)
    val remainingOverrideMinutes: StateFlow<Int> = _remainingOverrideMinutes.asStateFlow()

    init {
        Log.d("ScreenTimeGate", "ScreenTimeLimitReachedViewModel init, state=${enforcementState.value}")
        startOverrideTicker()
    }

    private fun startOverrideTicker() {
        try {
            viewModelScope.launch {
                while (isActive) {
                    val session = enforcementState.value?.overrideSession ?: sessionManager.getActiveSession()
                    if (session != null && session.isCurrentlyActive()) {
                        _remainingOverrideMinutes.value = session.remainingMinutes()
                    } else {
                        _remainingOverrideMinutes.value = 0
                    }
                    delay(5000L) // Refresh every 5 seconds
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    fun evaluateSamplePackages(): List<AppEnforcementDecision> {
        val currentEnforcement = enforcementState.value
        val currentPolicy = policy.value
        return listOf(
            "com.niqob.app",
            "com.android.settings",
            "com.android.chrome",
            "com.google.android.youtube"
        ).map { pkg ->
            AppEnforcementEvaluator.evaluate(
                packageName = pkg,
                enforcementState = currentEnforcement,
                policy = currentPolicy
            )
        }
    }

    /**
     * Authenticates parent PIN for a time-bounded screen-time gate override.
     * Enforces rate-limiting (3 fails = 60s cooldown, 5 fails = 15m lockout).
     */
    suspend fun authenticateParentPin(
        pin: String,
        durationMinutes: Int = ParentOverrideSession.DEFAULT_DURATION_MINUTES
    ): Boolean {
        _parentOverrideState.value = ParentOverrideUiState.Authenticating

        val result = sessionManager.attemptOverride(pin, durationMinutes)
        return when (result) {
            is OverrideAttemptResult.Success -> {
                Log.i("ScreenTimeGate", "Parent PIN override authenticated successfully: duration=${result.session.durationMinutes}m")
                _parentOverrideState.value = ParentOverrideUiState.Success
                _remainingOverrideMinutes.value = result.session.remainingMinutes()
                true
            }
            is OverrideAttemptResult.InvalidPin -> {
                Log.w("ScreenTimeGate", "Parent PIN override rejected (invalid PIN)")
                val msg = if (result.remainingAttemptsBeforeThrottling > 1) {
                    "Incorrect parent PIN. ${result.remainingAttemptsBeforeThrottling} attempts remaining."
                } else {
                    "Incorrect parent PIN. 1 attempt remaining before cooldown."
                }
                _parentOverrideState.value = ParentOverrideUiState.Error(msg)
                false
            }
            is OverrideAttemptResult.CooldownActive -> {
                Log.w("ScreenTimeGate", "Parent PIN override blocked by cooldown: ${result.remainingSeconds}s remaining")
                val msg = "Too many attempts. Cooldown active for ${result.remainingSeconds}s."
                _parentOverrideState.value = ParentOverrideUiState.Cooldown(result.remainingSeconds, msg)
                false
            }
            is OverrideAttemptResult.LockoutActive -> {
                Log.w("ScreenTimeGate", "Parent PIN override blocked by lockout: ${result.remainingMinutes}m remaining")
                val msg = "Too many attempts. Security lockout active for ${result.remainingMinutes} min."
                _parentOverrideState.value = ParentOverrideUiState.Lockout(result.remainingMinutes, msg)
                false
            }
        }
    }

    fun resetParentOverrideState() {
        _parentOverrideState.value = ParentOverrideUiState.Idle
    }

    fun clearOverride() {
        sessionManager.clearOverride()
        _remainingOverrideMinutes.value = 0
    }

    fun resetToLiveServerLimit(serverLimitMinutes: Int = 120, serverLimitEnabled: Boolean = true) {
        val current = enforcementState.value
        Log.d("ScreenTimeGate", "resetToLiveServerLimit called, current=$current")
        val used = current?.usedMinutes ?: 0
        val date = current?.usageDate ?: ""
        val newState = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = used,
            dailyLimitMinutes = serverLimitMinutes,
            isEnabled = serverLimitEnabled,
            date = date
        )
        Log.d("ScreenTimeGate", "Updating enforcement to: $newState")
        enforcementRepository.updateEnforcementState(newState)
    }

    fun disableEnforcement() {
        val current = enforcementState.value
        Log.d("ScreenTimeGate", "disableEnforcement called, current=$current")
        val used = current?.usedMinutes ?: 0
        val limit = current?.dailyLimitMinutes ?: 120
        val date = current?.usageDate ?: ""
        val newState = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = used,
            dailyLimitMinutes = limit,
            isEnabled = false,
            date = date
        )
        Log.d("ScreenTimeGate", "Updating enforcement to: $newState")
        enforcementRepository.updateEnforcementState(newState)
    }
}

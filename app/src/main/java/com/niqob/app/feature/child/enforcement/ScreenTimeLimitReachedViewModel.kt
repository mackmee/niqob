package com.niqob.app.feature.child.enforcement

import android.util.Log
import androidx.lifecycle.ViewModel
import com.niqob.app.data.local.enforcement.LocalParentOverrideAuthenticator
import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.ParentOverrideAuthenticator
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementDecision
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ParentOverrideUiState {
    data object Idle : ParentOverrideUiState()
    data object Authenticating : ParentOverrideUiState()
    data object Success : ParentOverrideUiState()
    data class Error(val message: String) : ParentOverrideUiState()
}

class ScreenTimeLimitReachedViewModel(
    private val enforcementRepository: ScreenTimeEnforcementRepository = LocalScreenTimeEnforcementRepository.getInstance(),
    private val appPolicyRepository: AppEnforcementPolicyRepository = LocalAppEnforcementPolicyRepository.getInstance(),
    private val parentOverrideAuthenticator: ParentOverrideAuthenticator = LocalParentOverrideAuthenticator()
) : ViewModel() {

    val enforcementState: StateFlow<ScreenTimeEnforcementState?> = enforcementRepository.getEnforcementState()
    val policy: StateFlow<AppEnforcementPolicy> = appPolicyRepository.getPolicy()

    private val _parentOverrideState = MutableStateFlow<ParentOverrideUiState>(ParentOverrideUiState.Idle)
    val parentOverrideState: StateFlow<ParentOverrideUiState> = _parentOverrideState.asStateFlow()

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

    init {
        Log.d("ScreenTimeGate", "ScreenTimeLimitReachedViewModel init, state=${enforcementState.value}")
    }

    /**
     * Authenticates parent PIN for temporary screen-time gate override.
     * When successful, applies override to repository without modifying permanent policy or Supabase limits.
     */
    suspend fun authenticateParentPin(pin: String): Boolean {
        _parentOverrideState.value = ParentOverrideUiState.Authenticating
        val isValid = parentOverrideAuthenticator.authenticate(pin)
        return if (isValid) {
            Log.i("ScreenTimeGate", "Parent PIN override authenticated successfully")
            enforcementRepository.setParentOverrideActive(true)
            _parentOverrideState.value = ParentOverrideUiState.Success
            true
        } else {
            Log.w("ScreenTimeGate", "Parent PIN override rejected (invalid PIN)")
            _parentOverrideState.value = ParentOverrideUiState.Error("Incorrect parent PIN. Please try again.")
            false
        }
    }

    fun resetParentOverrideState() {
        _parentOverrideState.value = ParentOverrideUiState.Idle
    }

    /**
     * Resets simulated limit to standard server limit (120 min), returning to within limit.
     */
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

    /**
     * Disables enforcement for development verification.
     */
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

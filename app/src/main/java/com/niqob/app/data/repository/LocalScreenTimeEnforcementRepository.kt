package com.niqob.app.data.repository

import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalScreenTimeEnforcementRepository : ScreenTimeEnforcementRepository {

    private val _enforcementState = MutableStateFlow<ScreenTimeEnforcementState?>(null)

    override fun getEnforcementState(): StateFlow<ScreenTimeEnforcementState?> {
        return _enforcementState.asStateFlow()
    }

    override fun updateEnforcementState(state: ScreenTimeEnforcementState) {
        _enforcementState.value = state
    }

    override fun setParentOverrideActive(active: Boolean) {
        val current = _enforcementState.value
        if (current != null) {
            _enforcementState.value = current.copy(isParentOverrideActive = active)
        }
    }

    override fun clear() {
        _enforcementState.value = null
    }

    companion object {
        @Volatile
        private var instance: LocalScreenTimeEnforcementRepository? = null

        fun getInstance(): LocalScreenTimeEnforcementRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalScreenTimeEnforcementRepository().also { instance = it }
            }
        }

        fun resetForTesting() {
            instance = null
        }
    }
}

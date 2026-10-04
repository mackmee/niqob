package com.niqob.app.fakes

import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeScreenTimeEnforcementRepository : ScreenTimeEnforcementRepository {

    private val _enforcementState = MutableStateFlow<ScreenTimeEnforcementState?>(null)
    var updateCount: Int = 0

    override fun getEnforcementState(): StateFlow<ScreenTimeEnforcementState?> {
        return _enforcementState.asStateFlow()
    }

    override fun updateEnforcementState(state: ScreenTimeEnforcementState) {
        _enforcementState.value = state
        updateCount++
    }

    override fun setParentOverrideActive(active: Boolean) {
        val current = _enforcementState.value
        if (current != null) {
            _enforcementState.value = current.copy(isParentOverrideActive = active)
            updateCount++
        }
    }

    override fun clear() {
        _enforcementState.value = null
    }
}

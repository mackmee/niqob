package com.niqob.app.fakes

import com.niqob.app.domain.model.ParentOverrideSession
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

    override fun setParentOverrideSession(session: ParentOverrideSession?) {
        val current = _enforcementState.value
        if (current != null) {
            val isActive = session != null && session.isActive
            _enforcementState.value = current.copy(
                overrideSession = session,
                isParentOverrideActive = isActive
            )
            updateCount++
        }
    }

    override fun setParentOverrideActive(active: Boolean) {
        if (active) {
            val session = ParentOverrideSession.create()
            setParentOverrideSession(session)
        } else {
            setParentOverrideSession(null)
        }
    }

    override fun getActiveOverrideSession(): ParentOverrideSession? {
        val session = _enforcementState.value?.overrideSession
        if (session != null && session.isCurrentlyActive()) {
            return session
        }
        return null
    }

    override fun clear() {
        _enforcementState.value = null
    }
}

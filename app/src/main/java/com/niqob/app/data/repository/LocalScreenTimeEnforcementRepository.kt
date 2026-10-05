package com.niqob.app.data.repository

import com.niqob.app.domain.enforcement.ParentOverrideSessionStore
import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalScreenTimeEnforcementRepository(
    private val sessionStore: ParentOverrideSessionStore? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ScreenTimeEnforcementRepository {

    private val _enforcementState = MutableStateFlow<ScreenTimeEnforcementState?>(null)

    override fun getEnforcementState(): StateFlow<ScreenTimeEnforcementState?> {
        return _enforcementState.asStateFlow()
    }

    override fun updateEnforcementState(state: ScreenTimeEnforcementState) {
        // Defensively preserve any active non-expired override session across updates
        val activeSession = state.overrideSession ?: getActiveOverrideSession()
        _enforcementState.value = if (activeSession != null && activeSession.isCurrentlyActive(timeProvider())) {
            state.copy(overrideSession = activeSession)
        } else {
            state.copy(overrideSession = null)
        }
    }

    override fun setParentOverrideSession(session: ParentOverrideSession?) {
        if (session != null) {
            sessionStore?.save(session)
        } else {
            sessionStore?.clear()
        }
        val current = _enforcementState.value
        if (current != null) {
            val isActive = session != null && session.isCurrentlyActive(timeProvider())
            _enforcementState.value = current.copy(
                overrideSession = session,
                isParentOverrideActive = isActive
            )
        }
    }

    override fun setParentOverrideActive(active: Boolean) {
        if (active) {
            val session = ParentOverrideSession.create(
                durationMinutes = ParentOverrideSession.DEFAULT_DURATION_MINUTES,
                activatedAtMillis = timeProvider()
            )
            setParentOverrideSession(session)
        } else {
            setParentOverrideSession(null)
        }
    }

    override fun getActiveOverrideSession(): ParentOverrideSession? {
        val currentSession = _enforcementState.value?.overrideSession
        if (currentSession != null && currentSession.isCurrentlyActive(timeProvider())) {
            return currentSession
        }
        val stored = sessionStore?.load(timeProvider())
        if (stored != null && stored.isCurrentlyActive(timeProvider())) {
            return stored
        }
        return null
    }

    override fun clear() {
        sessionStore?.clear()
        _enforcementState.value = null
    }

    companion object {
        @Volatile
        private var instance: LocalScreenTimeEnforcementRepository? = null

        fun getInstance(sessionStore: ParentOverrideSessionStore? = null): LocalScreenTimeEnforcementRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalScreenTimeEnforcementRepository(sessionStore).also { instance = it }
            }
        }

        fun resetForTesting() {
            instance = null
        }
    }
}

package com.niqob.app.domain.repository

import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import kotlinx.coroutines.flow.StateFlow

interface ScreenTimeEnforcementRepository {
    fun getEnforcementState(): StateFlow<ScreenTimeEnforcementState?>
    fun updateEnforcementState(state: ScreenTimeEnforcementState)
    fun setParentOverrideActive(active: Boolean)
    fun setParentOverrideSession(session: ParentOverrideSession?)
    fun getActiveOverrideSession(): ParentOverrideSession?
    fun clear()
}

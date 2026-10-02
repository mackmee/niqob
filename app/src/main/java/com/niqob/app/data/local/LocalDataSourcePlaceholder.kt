package com.niqob.app.data.local

import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.UserMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder for local persistence (e.g. Room Database or Jetpack DataStore).
 * In Phase 1, keeps in-memory state with the required initial defaults:
 * - Device: Not connected
 * - Screen Time: 0 min
 * - Allowed Apps: 0
 * - Safety Alerts: 0
 */
class LocalDataSourcePlaceholder {

    companion object {
        @Volatile
        private var instance: LocalDataSourcePlaceholder? = null

        fun getInstance(): LocalDataSourcePlaceholder {
            return instance ?: synchronized(this) {
                instance ?: LocalDataSourcePlaceholder().also { instance = it }
            }
        }

        fun resetInstanceForTests() {
            instance = null
        }
    }

    private val _userMode = MutableStateFlow(UserMode.UNSELECTED)
    val userMode: Flow<UserMode> = _userMode.asStateFlow()

    private val _childDeviceStatus = MutableStateFlow(
        ChildDeviceStatus(
            isConnected = false,
            deviceName = "Not connected",
            screenTimeMinutes = 0,
            allowedAppsCount = 0,
            safetyAlertsCount = 0
        )
    )
    val childDeviceStatus: Flow<ChildDeviceStatus> = _childDeviceStatus.asStateFlow()

    fun setUserMode(mode: UserMode) {
        _userMode.value = mode
    }

    fun updateChildStatus(status: ChildDeviceStatus) {
        _childDeviceStatus.value = status
    }
}

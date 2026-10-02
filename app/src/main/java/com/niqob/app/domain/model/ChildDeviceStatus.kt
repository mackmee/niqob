package com.niqob.app.domain.model

data class ChildDeviceStatus(
    val isConnected: Boolean = false,
    val deviceName: String = "Not connected",
    val screenTimeMinutes: Int = 0,
    val allowedAppsCount: Int = 0,
    val safetyAlertsCount: Int = 0
)

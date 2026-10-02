package com.niqob.app.domain.model

enum class ModuleType {
    CHILD_MODE,
    PARENT_MODE,
    DEVICE_PAIRING,
    APP_PERMISSIONS,
    SCREEN_TIME,
    PARENT_AUTHENTICATION,
    SAFETY_MONITORING,
    AI_TUTOR,
    AGE_BASED_PERMISSIONS,
    SETTINGS
}

data class FeatureModule(
    val type: ModuleType,
    val title: String,
    val description: String,
    val isReady: Boolean = false
)

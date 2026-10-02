package com.niqob.app.domain.model

data class ScreenTimeLimit(
    val id: String? = null,
    val familyId: String,
    val deviceId: String,
    val dailyLimitMinutes: Int = 120,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

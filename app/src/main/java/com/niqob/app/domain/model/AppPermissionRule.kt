package com.niqob.app.domain.model

data class AppPermissionRule(
    val packageName: String,
    val appName: String,
    val isAllowed: Boolean = true,
    val dailyLimitMinutes: Int? = null,
    val ageCategory: String = "All"
)

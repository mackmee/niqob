package com.niqob.app.domain.model

/**
 * Domain model representing the child's daily screen time aggregate summary
 * synchronized with Supabase cloud storage.
 */
data class ScreenTimeDailyUsage(
    val id: String? = null,
    val familyId: String? = null,
    val deviceId: String,
    val usageDate: String, // ISO date format "YYYY-MM-DD"
    val totalUsageMinutes: Int,
    val dailyLimitMinutes: Int = 120,
    val remainingMinutes: Int = 0,
    val usagePercentage: Int = 0,
    val isLimitReached: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val statusText: String
        get() = if (isLimitReached) "Limit reached" else "Within limit"
}

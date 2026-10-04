package com.niqob.app.domain.model

/**
 * Domain model representing the aggregated screen-time summary
 * across all applications on a child device for a specific day or time window.
 */
data class DailyUsageSummary(
    val date: String, // "YYYY-MM-DD"
    val totalScreenTimeMillis: Long,
    val appUsages: List<AppUsageInfo> = emptyList(),
    val startTimeMillis: Long = 0L,
    val endTimeMillis: Long = 0L
) {
    val totalScreenTimeMinutes: Int
        get() = (totalScreenTimeMillis / (1000 * 60)).toInt()

    val totalScreenTimeHours: Double
        get() = totalScreenTimeMillis / (1000.0 * 60.0 * 60.0)
}

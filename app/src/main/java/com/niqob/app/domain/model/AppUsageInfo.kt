package com.niqob.app.domain.model

/**
 * Domain model representing recorded application screen-time / usage duration
 * for a specific package on a given day.
 */
data class AppUsageInfo(
    val packageName: String,
    val totalTimeInForegroundMillis: Long,
    val usageDate: String, // ISO date representation "YYYY-MM-DD"
    val lastTimeUsed: Long = 0L,
    val appLabel: String? = null
) {
    val totalTimeInForegroundMinutes: Int
        get() = (totalTimeInForegroundMillis / (1000 * 60)).toInt()

    val totalTimeInForegroundSeconds: Long
        get() = totalTimeInForegroundMillis / 1000
}

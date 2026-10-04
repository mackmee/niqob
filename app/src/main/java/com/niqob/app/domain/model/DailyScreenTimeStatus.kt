package com.niqob.app.domain.model

/**
 * Domain model representing the calculated daily screen-time status
 * of the child device against the configured daily limit quota.
 */
data class DailyScreenTimeStatus(
    val date: String,
    val usedMinutes: Int,
    val dailyLimitMinutes: Int,
    val remainingMinutes: Int,
    val usagePercentage: Int,
    val isLimitReached: Boolean,
    val isEnabled: Boolean
) {
    val isWithinLimit: Boolean
        get() = !isLimitReached

    val clampedPercentage: Int
        get() = minOf(100, maxOf(0, usagePercentage))

    val progressFraction: Float
        get() = if (dailyLimitMinutes <= 0) {
            if (isLimitReached) 1f else 0f
        } else {
            minOf(1f, maxOf(0f, usedMinutes.toFloat() / dailyLimitMinutes.toFloat()))
        }

    val statusText: String
        get() = when {
            !isEnabled -> "Screen Time limit disabled"
            isLimitReached -> "Limit reached"
            else -> "Within limit"
        }
}

package com.niqob.app.domain.calculator

import com.niqob.app.domain.model.DailyScreenTimeStatus
import kotlin.math.roundToInt

/**
 * Pure domain-level calculator that calculates remaining minutes, percentage,
 * and limit reached status without depending on any Android APIs.
 */
object DailyScreenTimeCalculator {

    fun calculate(
        usedMinutes: Int,
        dailyLimitMinutes: Int,
        isEnabled: Boolean,
        date: String = ""
    ): DailyScreenTimeStatus {
        val safeUsedMinutes = maxOf(0, usedMinutes)
        val clampedLimit = maxOf(0, minOf(1440, dailyLimitMinutes))

        val remainingMinutes = maxOf(0, clampedLimit - safeUsedMinutes)
        val isLimitReached = isEnabled && (safeUsedMinutes >= clampedLimit)

        val usagePercentage = when {
            clampedLimit == 0 -> {
                if (safeUsedMinutes > 0 || isEnabled) 100 else 0
            }
            else -> {
                ((safeUsedMinutes.toDouble() / clampedLimit.toDouble()) * 100.0).roundToInt()
            }
        }

        return DailyScreenTimeStatus(
            date = date,
            usedMinutes = safeUsedMinutes,
            dailyLimitMinutes = clampedLimit,
            remainingMinutes = remainingMinutes,
            usagePercentage = usagePercentage,
            isLimitReached = isLimitReached,
            isEnabled = isEnabled
        )
    }
}

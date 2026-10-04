package com.niqob.app.domain.model

enum class ScreenTimeEnforcementStatus(val displayName: String) {
    DISABLED("Enforcement disabled"),
    WITHIN_LIMIT("Within limit"),
    LIMIT_REACHED("Limit reached")
}

data class ScreenTimeEnforcementState(
    val isEnforcementEnabled: Boolean,
    val isLimitReached: Boolean,
    val usedMinutes: Int,
    val dailyLimitMinutes: Int,
    val remainingMinutes: Int,
    val usagePercentage: Int,
    val usageDate: String,
    val isParentOverrideActive: Boolean = false,
    val status: ScreenTimeEnforcementStatus = when {
        !isEnforcementEnabled -> ScreenTimeEnforcementStatus.DISABLED
        isLimitReached -> ScreenTimeEnforcementStatus.LIMIT_REACHED
        else -> ScreenTimeEnforcementStatus.WITHIN_LIMIT
    }
) {
    /**
     * Enforcement is active only when the parent has enabled screen time limits
     * AND the child's usage has reached or exceeded the configured quota
     * AND parent override is not currently active.
     */
    val isEnforcementActive: Boolean
        get() = isEnforcementEnabled && isLimitReached && !isParentOverrideActive

    val isWithinLimit: Boolean
        get() = !isLimitReached

    companion object {
        fun fromDailyScreenTimeStatus(dailyStatus: DailyScreenTimeStatus): ScreenTimeEnforcementState {
            val enforcementStatus = when {
                !dailyStatus.isEnabled -> ScreenTimeEnforcementStatus.DISABLED
                dailyStatus.isLimitReached -> ScreenTimeEnforcementStatus.LIMIT_REACHED
                else -> ScreenTimeEnforcementStatus.WITHIN_LIMIT
            }

            return ScreenTimeEnforcementState(
                isEnforcementEnabled = dailyStatus.isEnabled,
                isLimitReached = dailyStatus.isLimitReached,
                usedMinutes = dailyStatus.usedMinutes,
                dailyLimitMinutes = dailyStatus.dailyLimitMinutes,
                remainingMinutes = dailyStatus.remainingMinutes,
                usagePercentage = dailyStatus.usagePercentage,
                usageDate = dailyStatus.date,
                status = enforcementStatus
            )
        }
    }
}

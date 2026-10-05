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
    val overrideSession: ParentOverrideSession? = null,
    val isParentOverrideActive: Boolean = overrideSession?.isCurrentlyActive() == true,
    val status: ScreenTimeEnforcementStatus = when {
        !isEnforcementEnabled -> ScreenTimeEnforcementStatus.DISABLED
        isLimitReached -> ScreenTimeEnforcementStatus.LIMIT_REACHED
        else -> ScreenTimeEnforcementStatus.WITHIN_LIMIT
    }
) {
    /**
     * Effective parent override flag.
     * When [overrideSession] is present, its expiration timestamp is authoritative:
     * if current time >= expiresAtMillis, the session is expired and override is inactive.
     */
    fun isParentOverrideActiveEffective(currentTimeMillis: Long = System.currentTimeMillis()): Boolean =
        overrideSession?.isCurrentlyActive(currentTimeMillis) ?: isParentOverrideActive

    val isParentOverrideActiveEffective: Boolean
        get() = isParentOverrideActiveEffective(System.currentTimeMillis())

    /**
     * Enforcement is active only when the parent has enabled screen time limits
     * AND the child's usage has reached or exceeded the configured quota
     * AND parent override is absent or expired.
     */
    fun isEnforcementActive(currentTimeMillis: Long = System.currentTimeMillis()): Boolean =
        isEnforcementEnabled && isLimitReached && !isParentOverrideActiveEffective(currentTimeMillis)

    val isEnforcementActive: Boolean
        get() = isEnforcementActive(System.currentTimeMillis())

    val isWithinLimit: Boolean
        get() = !isLimitReached

    companion object {
        fun fromDailyScreenTimeStatus(
            dailyStatus: DailyScreenTimeStatus,
            overrideSession: ParentOverrideSession? = null
        ): ScreenTimeEnforcementState {
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
                overrideSession = overrideSession,
                status = enforcementStatus
            )
        }
    }
}

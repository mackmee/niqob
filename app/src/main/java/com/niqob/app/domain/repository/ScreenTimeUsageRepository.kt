package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ScreenTimeDailyUsage

interface ScreenTimeUsageRepository {
    /**
     * Upserts the daily screen-time summary for a specific device and date.
     * Guaranteed to update existing record on (deviceId, usageDate) collision without duplicates.
     */
    suspend fun upsertDailyUsage(
        deviceId: String,
        usageDate: String,
        totalUsageMinutes: Int,
        dailyLimitMinutes: Int? = null,
        remainingMinutes: Int? = null,
        usagePercentage: Int? = null,
        isLimitReached: Boolean? = null
    ): Result<ScreenTimeDailyUsage>

    /**
     * Retrieves the daily screen-time summary for a specific device and date.
     * Returns Result.Success(null) if no usage has been recorded yet today.
     */
    suspend fun getDailyUsage(
        deviceId: String,
        usageDate: String
    ): Result<ScreenTimeDailyUsage?>
}

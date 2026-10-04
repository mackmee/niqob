package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.DailyUsageSummary

interface UsageStatsRepository {
    /**
     * Checks whether the PACKAGE_USAGE_STATS permission (AppOps) is granted on this device.
     */
    fun hasUsageAccessPermission(): Boolean

    /**
     * Queries app usage for today (midnight 00:00:00 to now) in the device's timezone.
     * @param excludeSelf whether to exclude NIQOB's own package from tracked usage.
     */
    suspend fun getTodayUsage(excludeSelf: Boolean = true): Result<DailyUsageSummary>

    /**
     * Queries app usage for an explicit time range [startTimeMillis, endTimeMillis].
     * @param excludeSelf whether to exclude NIQOB's own package from tracked usage.
     */
    suspend fun getUsageForRange(
        startTimeMillis: Long,
        endTimeMillis: Long,
        excludeSelf: Boolean = true
    ): Result<DailyUsageSummary>
}

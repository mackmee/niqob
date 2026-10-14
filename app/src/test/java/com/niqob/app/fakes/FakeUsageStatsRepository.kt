package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.AppUsageInfo
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.domain.repository.UsageStatsRepository

class FakeUsageStatsRepository(
    var permissionGranted: Boolean = true,
    var mockUsageSummary: DailyUsageSummary? = null,
    var shouldFail: Boolean = false,
    var failureException: Exception = Exception("Simulated FakeUsageStatsRepository failure"),
    var getTodayUsageCallCount: Int = 0
) : UsageStatsRepository {

    override fun hasUsageAccessPermission(): Boolean {
        return permissionGranted
    }

    override suspend fun getTodayUsage(excludeSelf: Boolean): Result<DailyUsageSummary> {
        getTodayUsageCallCount++
        if (shouldFail) return Result.Error(failureException)
        if (!permissionGranted) {
            return Result.Error(SecurityException("Usage access permission not granted"))
        }
        val summary = mockUsageSummary ?: DailyUsageSummary(
            date = "2026-10-02",
            totalScreenTimeMillis = 0L,
            appUsages = emptyList()
        )
        return Result.Success(summary)
    }

    override suspend fun getUsageForRange(
        startTimeMillis: Long,
        endTimeMillis: Long,
        excludeSelf: Boolean
    ): Result<DailyUsageSummary> {
        if (shouldFail) return Result.Error(failureException)
        if (!permissionGranted) {
            return Result.Error(SecurityException("Usage access permission not granted"))
        }
        if (startTimeMillis > endTimeMillis) {
            return Result.Error(IllegalArgumentException("Start time cannot be after end time."))
        }
        val summary = mockUsageSummary ?: DailyUsageSummary(
            date = "2026-10-02",
            totalScreenTimeMillis = 0L,
            appUsages = emptyList(),
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis
        )
        return Result.Success(summary)
    }
}

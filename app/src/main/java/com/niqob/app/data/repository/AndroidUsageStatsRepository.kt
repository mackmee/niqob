package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.usage.RawUsageStat
import com.niqob.app.data.local.usage.UsageStatsDataSource
import com.niqob.app.domain.model.AppUsageInfo
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.domain.repository.UsageStatsRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AndroidUsageStatsRepository(
    private val dataSource: UsageStatsDataSource,
    private val selfPackageName: String = "com.niqob.app",
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val appLabelResolver: ((String) -> String?)? = null
) : UsageStatsRepository {

    override fun hasUsageAccessPermission(): Boolean {
        return dataSource.hasUsageStatsPermission()
    }

    override suspend fun getTodayUsage(excludeSelf: Boolean): Result<DailyUsageSummary> {
        val now = timeProvider()
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        return getUsageForRange(
            startTimeMillis = startOfDay,
            endTimeMillis = now,
            excludeSelf = excludeSelf
        )
    }

    override suspend fun getUsageForRange(
        startTimeMillis: Long,
        endTimeMillis: Long,
        excludeSelf: Boolean
    ): Result<DailyUsageSummary> {
        if (startTimeMillis > endTimeMillis) {
            return Result.Error(IllegalArgumentException("Start time cannot be after end time."))
        }

        if (!hasUsageAccessPermission()) {
            return Result.Error(
                SecurityException("Usage access permission (PACKAGE_USAGE_STATS) has not been granted.")
            )
        }

        return try {
            val rawStats = dataSource.getUsageStats(startTimeMillis, endTimeMillis)
            val aggregated = aggregateUsageStats(
                rawStats = rawStats,
                startTimeMillis = startTimeMillis,
                endTimeMillis = endTimeMillis,
                excludeSelf = excludeSelf
            )
            Result.Success(aggregated)
        } catch (e: Exception) {
            Result.Error(Exception("Failed to query usage statistics: ${e.message}", e))
        }
    }

    fun aggregateUsageStats(
        rawStats: List<RawUsageStat>,
        startTimeMillis: Long,
        endTimeMillis: Long,
        excludeSelf: Boolean
    ): DailyUsageSummary {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = this@AndroidUsageStatsRepository.timeZone
        }
        val dateString = dateFormat.format(Date(startTimeMillis))

        // Aggregate by package name
        val packageMap = mutableMapOf<String, Long>()
        val lastUsedMap = mutableMapOf<String, Long>()

        for (stat in rawStats) {
            val pkg = stat.packageName.trim()
            if (pkg.isBlank()) continue
            if (excludeSelf && pkg == selfPackageName) continue
            if (stat.totalTimeInForeground <= 0) continue

            val currentMillis = packageMap.getOrDefault(pkg, 0L)
            packageMap[pkg] = currentMillis + stat.totalTimeInForeground

            val currentLastUsed = lastUsedMap.getOrDefault(pkg, 0L)
            if (stat.lastTimeUsed > currentLastUsed) {
                lastUsedMap[pkg] = stat.lastTimeUsed
            }
        }

        val appUsages = packageMap.map { (pkg, totalMillis) ->
            AppUsageInfo(
                packageName = pkg,
                totalTimeInForegroundMillis = totalMillis,
                usageDate = dateString,
                lastTimeUsed = lastUsedMap.getOrDefault(pkg, 0L),
                appLabel = appLabelResolver?.invoke(pkg)
            )
        }.sortedByDescending { it.totalTimeInForegroundMillis }

        val totalScreenTimeMillis = appUsages.sumOf { it.totalTimeInForegroundMillis }

        return DailyUsageSummary(
            date = dateString,
            totalScreenTimeMillis = totalScreenTimeMillis,
            appUsages = appUsages,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis
        )
    }
}

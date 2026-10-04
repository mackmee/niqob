package com.niqob.app.data.local.usage

data class RawUsageStat(
    val packageName: String,
    val totalTimeInForeground: Long,
    val firstTimeStamp: Long = 0L,
    val lastTimeStamp: Long = 0L,
    val lastTimeUsed: Long = 0L
)

interface UsageStatsDataSource {
    fun hasUsageStatsPermission(): Boolean
    fun getUsageStats(startTime: Long, endTime: Long): List<RawUsageStat>
}

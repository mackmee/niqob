package com.niqob.app.fakes

import com.niqob.app.data.local.usage.RawUsageStat
import com.niqob.app.data.local.usage.UsageStatsDataSource

class FakeUsageStatsDataSource(
    var permissionGranted: Boolean = true,
    var shouldThrowOnQuery: Boolean = false
) : UsageStatsDataSource {

    private val statsList = mutableListOf<RawUsageStat>()

    fun setStats(stats: List<RawUsageStat>) {
        statsList.clear()
        statsList.addAll(stats)
    }

    fun addStat(stat: RawUsageStat) {
        statsList.add(stat)
    }

    fun clear() {
        statsList.clear()
    }

    override fun hasUsageStatsPermission(): Boolean {
        return permissionGranted
    }

    override fun getUsageStats(startTime: Long, endTime: Long): List<RawUsageStat> {
        if (shouldThrowOnQuery) {
            throw RuntimeException("Simulated Android UsageStatsManager failure")
        }
        return statsList.toList()
    }
}

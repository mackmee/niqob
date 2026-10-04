package com.niqob.app.data.local.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

class AndroidUsageStatsDataSource(
    private val context: Context
) : UsageStatsDataSource {

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    override fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    override fun getUsageStats(startTime: Long, endTime: Long): List<RawUsageStat> {
        val manager = usageStatsManager ?: return emptyList()
        return try {
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )?.takeIf { it.isNotEmpty() }
                ?: manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    endTime
                )
                ?: emptyList()

            stats.map { stat ->
                RawUsageStat(
                    packageName = stat.packageName ?: "",
                    totalTimeInForeground = stat.totalTimeInForeground,
                    firstTimeStamp = stat.firstTimeStamp,
                    lastTimeStamp = stat.lastTimeStamp,
                    lastTimeUsed = stat.lastTimeUsed
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

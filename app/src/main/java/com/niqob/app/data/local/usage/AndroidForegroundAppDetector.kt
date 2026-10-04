package com.niqob.app.data.local.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.niqob.app.domain.enforcement.ForegroundAppDetector
import com.niqob.app.domain.model.ForegroundAppInfo

/**
 * Android implementation of [ForegroundAppDetector] using Android's [UsageStatsManager].
 *
 * Responsibilities:
 * - Queries recent usage events to identify the most recent foreground application.
 * - Respects Usage Access permission states safely.
 * - Does NOT access message contents, notifications, or screen pixels.
 * - Does NOT use AccessibilityService or any dangerous permissions.
 */
class AndroidForegroundAppDetector(
    private val context: Context,
    private val selfPackageName: String = "com.niqob.app",
    private val permissionChecker: (() -> Boolean)? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForegroundAppDetector {

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    fun hasUsageStatsPermission(): Boolean {
        if (permissionChecker != null) {
            return permissionChecker.invoke()
        }
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

    override suspend fun getCurrentForegroundApp(excludeSelf: Boolean): ForegroundAppInfo? {
        if (!hasUsageStatsPermission()) {
            return null
        }
        val manager = usageStatsManager ?: return null

        return try {
            val now = timeProvider()
            val windowMinutes = 15L
            val startTime = now - (windowMinutes * 60 * 1000L)

            var latestPackage: String? = null
            var latestTimestamp = 0L

            // 1. First attempt: Query recent usage events for precise activity resumed events
            val events = manager.queryEvents(startTime, now)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    type == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == 1
                }

                if (isForegroundEvent) {
                    val pkg = event.packageName?.trim()
                    if (!pkg.isNullOrBlank()) {
                        if (excludeSelf && pkg == selfPackageName) {
                            continue
                        }
                        if (event.timeStamp >= latestTimestamp) {
                            latestPackage = pkg
                            latestTimestamp = event.timeStamp
                        }
                    }
                }
            }

            // 2. Fallback: If no activity resume events in the last 15 minutes, query best usage stats
            if (latestPackage.isNullOrBlank()) {
                val dayStartTime = now - (24 * 60 * 60 * 1000L)
                val stats = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    dayStartTime,
                    now
                )

                val eligibleStats = stats?.filter { stat ->
                    val pkg = stat.packageName?.trim()
                    !pkg.isNullOrBlank() && (!excludeSelf || pkg != selfPackageName)
                }

                val mostRecent = eligibleStats?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null && mostRecent.lastTimeUsed > 0) {
                    latestPackage = mostRecent.packageName?.trim()
                    latestTimestamp = mostRecent.lastTimeUsed
                }
            }

            if (latestPackage.isNullOrBlank()) {
                null
            } else {
                ForegroundAppInfo(
                    packageName = latestPackage,
                    timestampMillis = if (latestTimestamp > 0) latestTimestamp else now
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

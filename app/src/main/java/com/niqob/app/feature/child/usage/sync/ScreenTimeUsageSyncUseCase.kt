package com.niqob.app.feature.child.usage.sync

import android.util.Log
import com.niqob.app.core.common.Result as NiqobResult
import com.niqob.app.domain.calculator.DailyScreenTimeCalculator
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.repository.ScreenTimeRepository
import com.niqob.app.domain.repository.ScreenTimeUsageRepository
import com.niqob.app.domain.repository.UsageStatsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

sealed class SyncExecutionResult {
    data class Success(
        val usedMinutes: Int,
        val remainingMinutes: Int,
        val percentage: Int,
        val isLimitReached: Boolean
    ) : SyncExecutionResult()

    data class Skipped(val reason: String) : SyncExecutionResult()
    data class Retry(val message: String?) : SyncExecutionResult()
    data class Failure(val message: String?) : SyncExecutionResult()
}

class ScreenTimeUsageSyncUseCase(
    private val usageStatsRepo: UsageStatsRepository,
    private val screenTimeRepo: ScreenTimeRepository,
    private val pairingRepo: PairingRepository,
    private val screenTimeUsageRepo: ScreenTimeUsageRepository,
    private val maxRetries: Int = 3
) {
    companion object {
        private const val TAG = "ScreenTimeSyncUseCase"
    }

    suspend fun executeSync(attemptCount: Int = 0): SyncExecutionResult {
        // 1. Verify child device is paired
        val session: PairingSession? = try {
            val sessionFlow = pairingRepo.getPairingSession()
            if (sessionFlow is StateFlow<PairingSession?>) {
                sessionFlow.value
            } else {
                withTimeoutOrNull(2000L) { sessionFlow.firstOrNull() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read pairing session: ${e.message}")
            null
        }

        val childDeviceId = session?.childDeviceId
        if (childDeviceId.isNullOrBlank() || session.status != PairingStatus.CONNECTED) {
            Log.d(TAG, "Device is not paired as connected child. Skipping automatic sync.")
            return SyncExecutionResult.Skipped("not_paired")
        }

        // 2. Verify Usage Access permission is granted
        val hasPermission = usageStatsRepo.hasUsageAccessPermission()
        if (!hasPermission) {
            Log.w(TAG, "Usage access permission (PACKAGE_USAGE_STATS) is not granted. Skipping sync.")
            return SyncExecutionResult.Skipped("permission_missing")
        }

        // 3. Query today's real UsageStats aggregate (excluding NIQOB app itself)
        val usageSummary = when (val usageResult = usageStatsRepo.getTodayUsage(excludeSelf = true)) {
            is NiqobResult.Success -> usageResult.data
            is NiqobResult.Error -> {
                val msg = usageResult.exception.message ?: "Failed to read today's usage stats"
                Log.e(TAG, msg)
                return if (attemptCount < maxRetries) {
                    SyncExecutionResult.Retry(msg)
                } else {
                    SyncExecutionResult.Failure(msg)
                }
            }
            is NiqobResult.Loading -> {
                return if (attemptCount < maxRetries) {
                    SyncExecutionResult.Retry("usage_query_loading")
                } else {
                    SyncExecutionResult.Failure("usage_query_timeout")
                }
            }
        }

        // 4. Query current server limit for child device
        var dailyLimitMinutes = 120
        var isLimitEnabled = true
        when (val limitResult = screenTimeRepo.getScreenTimeLimit(childDeviceId)) {
            is NiqobResult.Success -> {
                dailyLimitMinutes = limitResult.data.dailyLimitMinutes
                isLimitEnabled = limitResult.data.enabled
            }
            is NiqobResult.Error -> {
                Log.w(TAG, "Could not fetch limit from server, using default limit: ${limitResult.exception.message}")
            }
            is NiqobResult.Loading -> { /* use defaults */ }
        }

        // 5. Calculate DailyScreenTimeStatus using existing domain calculator
        val calculatedStatus = DailyScreenTimeCalculator.calculate(
            usedMinutes = usageSummary.totalScreenTimeMinutes,
            dailyLimitMinutes = dailyLimitMinutes,
            isEnabled = isLimitEnabled,
            date = usageSummary.date
        )

        // 6. Upload aggregate daily summary to Supabase
        val syncResult = screenTimeUsageRepo.upsertDailyUsage(
            deviceId = childDeviceId,
            usageDate = usageSummary.date,
            totalUsageMinutes = calculatedStatus.usedMinutes,
            dailyLimitMinutes = calculatedStatus.dailyLimitMinutes,
            remainingMinutes = calculatedStatus.remainingMinutes,
            usagePercentage = calculatedStatus.usagePercentage,
            isLimitReached = calculatedStatus.isLimitReached
        )

        return when (syncResult) {
            is NiqobResult.Success -> {
                Log.i(TAG, "Screen time usage sync succeeded: ${calculatedStatus.usedMinutes}m used, ${calculatedStatus.remainingMinutes}m remaining.")
                SyncExecutionResult.Success(
                    usedMinutes = calculatedStatus.usedMinutes,
                    remainingMinutes = calculatedStatus.remainingMinutes,
                    percentage = calculatedStatus.usagePercentage,
                    isLimitReached = calculatedStatus.isLimitReached
                )
            }
            is NiqobResult.Error -> {
                val errorMsg = syncResult.exception.message ?: "Failed to sync daily usage to Supabase"
                Log.e(TAG, "Screen time usage sync failed: $errorMsg")
                if (attemptCount < maxRetries) {
                    SyncExecutionResult.Retry(errorMsg)
                } else {
                    SyncExecutionResult.Failure(errorMsg)
                }
            }
            is NiqobResult.Loading -> SyncExecutionResult.Retry("sync_loading")
        }
    }
}

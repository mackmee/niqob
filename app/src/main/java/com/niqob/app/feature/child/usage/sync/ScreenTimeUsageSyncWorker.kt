package com.niqob.app.feature.child.usage.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.local.usage.AndroidUsageStatsDataSource
import com.niqob.app.data.repository.AndroidUsageStatsRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.data.repository.SupabaseScreenTimeRepository
import com.niqob.app.data.repository.SupabaseScreenTimeUsageRepository
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.repository.ScreenTimeRepository
import com.niqob.app.domain.repository.ScreenTimeUsageRepository
import com.niqob.app.domain.repository.UsageStatsRepository

class ScreenTimeUsageSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    usageStatsRepository: UsageStatsRepository? = null,
    screenTimeRepository: ScreenTimeRepository? = null,
    pairingRepository: PairingRepository? = null,
    screenTimeUsageRepository: ScreenTimeUsageRepository? = null,
    private val syncUseCase: ScreenTimeUsageSyncUseCase? = null
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScreenTimeSyncWorker"
    }

    // Default 2-arg constructor for WorkManager reflection
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext = appContext,
        workerParams = workerParams,
        usageStatsRepository = null,
        screenTimeRepository = null,
        pairingRepository = null,
        screenTimeUsageRepository = null,
        syncUseCase = null
    )

    private val effectiveUseCase: ScreenTimeUsageSyncUseCase = syncUseCase ?: run {
        val credStore = SharedPreferencesChildDeviceCredentialStore.getInstance(applicationContext)
        ScreenTimeUsageSyncUseCase(
            usageStatsRepo = usageStatsRepository ?: AndroidUsageStatsRepository(AndroidUsageStatsDataSource(applicationContext)),
            screenTimeRepo = screenTimeRepository ?: SupabaseScreenTimeRepository(credentialStore = credStore),
            pairingRepo = pairingRepository ?: SupabasePairingRepository.getInstance(applicationContext),
            screenTimeUsageRepo = screenTimeUsageRepository ?: SupabaseScreenTimeUsageRepository(credentialStore = credStore)
        )
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Executing WorkManager periodic sync (attempt $runAttemptCount)...")
        return when (val outcome = effectiveUseCase.executeSync(runAttemptCount)) {
            is SyncExecutionResult.Success -> {
                ScreenTimeSyncScheduler.recordSyncResult(
                    applicationContext,
                    status = ScreenTimeSyncScheduler.SYNC_STATUS_SUCCESS,
                    usedMinutes = outcome.usedMinutes,
                    remainingMinutes = outcome.remainingMinutes,
                    timestamp = System.currentTimeMillis()
                )
                Result.success(
                    workDataOf(
                        "usedMinutes" to outcome.usedMinutes,
                        "remainingMinutes" to outcome.remainingMinutes,
                        "percentage" to outcome.percentage,
                        "isLimitReached" to outcome.isLimitReached
                    )
                )
            }
            is SyncExecutionResult.Skipped -> {
                val status = if (outcome.reason == "not_paired") {
                    ScreenTimeSyncScheduler.SYNC_STATUS_SKIPPED_NOT_PAIRED
                } else {
                    ScreenTimeSyncScheduler.SYNC_STATUS_SKIPPED_PERMISSION
                }
                ScreenTimeSyncScheduler.recordSyncResult(
                    applicationContext,
                    status = status,
                    usedMinutes = 0,
                    remainingMinutes = 0
                )
                Result.success(workDataOf("reason" to outcome.reason))
            }
            is SyncExecutionResult.Retry -> {
                Result.retry()
            }
            is SyncExecutionResult.Failure -> {
                ScreenTimeSyncScheduler.recordSyncResult(
                    applicationContext,
                    status = ScreenTimeSyncScheduler.SYNC_STATUS_FAILED,
                    usedMinutes = 0,
                    remainingMinutes = 0,
                    error = outcome.message
                )
                Result.failure(workDataOf("error" to (outcome.message ?: "sync_failed")))
            }
        }
    }
}

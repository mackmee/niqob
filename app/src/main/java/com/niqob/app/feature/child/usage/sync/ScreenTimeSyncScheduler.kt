package com.niqob.app.feature.child.usage.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

data class SyncInfo(
    val lastSyncTimeMillis: Long,
    val status: String,
    val usedMinutes: Int,
    val remainingMinutes: Int,
    val errorMessage: String? = null,
    val isAutoSyncScheduled: Boolean = false
)

object ScreenTimeSyncScheduler {

    const val TAG_SCREEN_TIME_SYNC = "niqob_screen_time_sync"
    const val UNIQUE_PERIODIC_WORK_NAME = "niqob_screen_time_usage_periodic_sync"
    const val UNIQUE_ONE_TIME_WORK_NAME = "niqob_screen_time_usage_one_time_sync"

    const val PREFS_NAME = "niqob_screen_time_sync_prefs"
    const val KEY_LAST_SYNC_TIME_MILLIS = "last_sync_time_millis"
    const val KEY_LAST_SYNC_STATUS = "last_sync_status"
    const val KEY_LAST_SYNCED_USED = "last_synced_used_minutes"
    const val KEY_LAST_SYNCED_REMAINING = "last_synced_remaining_minutes"
    const val KEY_LAST_SYNC_ERROR = "last_sync_error"
    const val KEY_AUTO_SYNC_SCHEDULED = "auto_sync_scheduled"

    const val SYNC_STATUS_SUCCESS = "SUCCESS"
    const val SYNC_STATUS_FAILED = "FAILED"
    const val SYNC_STATUS_SKIPPED_NOT_PAIRED = "SKIPPED_NOT_PAIRED"
    const val SYNC_STATUS_SKIPPED_PERMISSION = "SKIPPED_PERMISSION"
    const val SYNC_STATUS_SKIPPED_ROLE = "SKIPPED_ROLE"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Schedules the unique periodic background sync job (every 15 minutes, with network requirement).
     * Uses ExistingPeriodicWorkPolicy.KEEP so subsequent calls do not duplicate or restart the work.
     */
    fun schedule(context: Context, forceUpdate: Boolean = false) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<ScreenTimeUsageSyncWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SCREEN_TIME_SYNC)
            .build()

        val policy = if (forceUpdate) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            policy,
            periodicWorkRequest
        )

        getPrefs(appContext).edit().putBoolean(KEY_AUTO_SYNC_SCHEDULED, true).apply()
    }

    /**
     * Cancels the periodic background sync job.
     */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        getPrefs(appContext).edit().putBoolean(KEY_AUTO_SYNC_SCHEDULED, false).apply()
    }

    /**
     * Checks whether the current device is reliably configured as an enrolled child device:
     * 1. Has non-blank child device ID and device secret in secure credential store.
     * 2. Current persisted role is UserMode.CHILD.
     * 3. Connected pairing session exists and matches stored device ID.
     */
    fun isEnrolledChildDevice(
        context: Context? = null,
        pairingRepository: PairingRepository? = null,
        credentialStore: ChildDeviceCredentialStore? = null
    ): Boolean {
        val credStore = credentialStore ?: (if (context != null) SharedPreferencesChildDeviceCredentialStore.getInstance(context) else null)
        if (credStore == null || !credStore.hasCredential()) {
            return false
        }
        val storedDeviceId = credStore.getDeviceId()
        val storedSecret = credStore.getDeviceSecret()
        if (storedDeviceId.isNullOrBlank() || storedSecret.isNullOrBlank()) {
            return false
        }

        val pairingRepo = pairingRepository ?: (if (context != null) SupabasePairingRepository.getInstance(context) else null)
            ?: return false
        val role = pairingRepo.getCurrentRoleValue()
        if (role != UserMode.CHILD) {
            return false
        }

        val session = (pairingRepo.getPairingSession() as? StateFlow<PairingSession?>)?.value
        if (session == null || session.status != PairingStatus.CONNECTED || session.childDeviceId != storedDeviceId) {
            return false
        }

        return true
    }

    /**
     * Reconciles background sync scheduling with current device enrollment status:
     * Schedules periodic sync ONLY if the device is a properly enrolled child;
     * Cancels any active periodic sync otherwise.
     */
    fun reconcileScheduling(
        context: Context,
        pairingRepository: PairingRepository? = null,
        credentialStore: ChildDeviceCredentialStore? = null
    ) {
        if (isEnrolledChildDevice(context, pairingRepository, credentialStore)) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    /**
     * Triggers an immediate one-time sync via WorkManager (e.g. for development verification).
     */
    fun syncNow(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<ScreenTimeUsageSyncWorker>()
            .setConstraints(constraints)
            .addTag(TAG_SCREEN_TIME_SYNC)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
    }

    /**
     * Checks whether automatic sync is marked as scheduled.
     */
    fun isScheduled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_SYNC_SCHEDULED, false)
    }

    /**
     * Records the outcome of a sync operation (called by worker or manual sync).
     */
    fun recordSyncResult(
        context: Context,
        status: String,
        usedMinutes: Int,
        remainingMinutes: Int,
        timestamp: Long = System.currentTimeMillis(),
        error: String? = null
    ) {
        getPrefs(context).edit().apply {
            putLong(KEY_LAST_SYNC_TIME_MILLIS, timestamp)
            putString(KEY_LAST_SYNC_STATUS, status)
            putInt(KEY_LAST_SYNCED_USED, usedMinutes)
            putInt(KEY_LAST_SYNCED_REMAINING, remainingMinutes)
            if (error != null) {
                putString(KEY_LAST_SYNC_ERROR, error)
            } else {
                remove(KEY_LAST_SYNC_ERROR)
            }
            apply()
        }
    }

    /**
     * Reads the persisted sync info.
     */
    fun getLastSyncInfo(context: Context): SyncInfo {
        val prefs = getPrefs(context)
        return SyncInfo(
            lastSyncTimeMillis = prefs.getLong(KEY_LAST_SYNC_TIME_MILLIS, 0L),
            status = prefs.getString(KEY_LAST_SYNC_STATUS, "NONE") ?: "NONE",
            usedMinutes = prefs.getInt(KEY_LAST_SYNCED_USED, 0),
            remainingMinutes = prefs.getInt(KEY_LAST_SYNCED_REMAINING, 0),
            errorMessage = prefs.getString(KEY_LAST_SYNC_ERROR, null),
            isAutoSyncScheduled = prefs.getBoolean(KEY_AUTO_SYNC_SCHEDULED, false)
        )
    }
}

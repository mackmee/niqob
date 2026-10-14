package com.niqob.app

import android.app.Application
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.local.enforcement.SharedPreferencesParentOverrideSessionStore
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.feature.child.usage.sync.ScreenTimeSyncScheduler

class NiqobApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize child device credential store with application context
        val credStore = SharedPreferencesChildDeviceCredentialStore.getInstance(applicationContext)

        // Initialize pairing repository with application context
        val pairingRepo = SupabasePairingRepository.getInstance(applicationContext)

        // Initialize persistent parent override store with application context
        val overrideStore = SharedPreferencesParentOverrideSessionStore.getInstance(applicationContext)
        LocalScreenTimeEnforcementRepository.getInstance(overrideStore)

        // Only schedule periodic screen time usage sync if the device is reliably an enrolled child;
        // otherwise cancel any existing scheduled sync job to prevent parent-mode or un-enrolled telemetry leakage.
        ScreenTimeSyncScheduler.reconcileScheduling(
            context = applicationContext,
            pairingRepository = pairingRepo,
            credentialStore = credStore
        )
    }
}

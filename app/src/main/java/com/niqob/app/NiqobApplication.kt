package com.niqob.app

import android.app.Application
import com.niqob.app.data.local.enforcement.SharedPreferencesParentOverrideSessionStore
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.feature.child.usage.sync.ScreenTimeSyncScheduler

class NiqobApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize pairing repository with application context
        SupabasePairingRepository.getInstance(applicationContext)

        // Initialize persistent parent override store with application context
        val overrideStore = SharedPreferencesParentOverrideSessionStore.getInstance(applicationContext)
        LocalScreenTimeEnforcementRepository.getInstance(overrideStore)

        // Schedule periodic screen time usage sync worker (every 15 minutes)
        // Uses ExistingPeriodicWorkPolicy.KEEP to guarantee a unique, non-duplicated background job.
        ScreenTimeSyncScheduler.schedule(applicationContext)
    }
}

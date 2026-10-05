package com.niqob.app.data.local.enforcement

import android.content.Context
import android.content.SharedPreferences
import com.niqob.app.domain.enforcement.ParentOverrideSessionStore
import com.niqob.app.domain.model.ParentOverrideSession

/**
 * Android [SharedPreferences] implementation of [ParentOverrideSessionStore].
 *
 * Persists session metadata so it survives process recreation, memory kills,
 * and device reboots. Does NOT persist credentials or PINs.
 * Expired sessions are automatically detected and purged on load.
 */
class SharedPreferencesParentOverrideSessionStore(
    private val preferences: SharedPreferences
) : ParentOverrideSessionStore {

    override fun save(session: ParentOverrideSession) {
        preferences.edit()
            .putBoolean(KEY_IS_ACTIVE, session.isActive)
            .putLong(KEY_ACTIVATED_AT, session.activatedAtMillis)
            .putLong(KEY_EXPIRES_AT, session.expiresAtMillis)
            .putInt(KEY_DURATION_MINUTES, session.durationMinutes)
            .apply()
    }

    override fun load(currentTimeMillis: Long): ParentOverrideSession? {
        val activatedAt = preferences.getLong(KEY_ACTIVATED_AT, 0L)
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        val isActive = preferences.getBoolean(KEY_IS_ACTIVE, false)
        val duration = preferences.getInt(KEY_DURATION_MINUTES, ParentOverrideSession.DEFAULT_DURATION_MINUTES)

        if (activatedAt == 0L || expiresAt == 0L || !isActive) {
            return null
        }

        // Strictly enforce expiration: if current time is past expiration, purge and return null
        if (currentTimeMillis >= expiresAt) {
            clear()
            return null
        }

        return ParentOverrideSession(
            isActive = true,
            activatedAtMillis = activatedAt,
            expiresAtMillis = expiresAt,
            durationMinutes = duration
        )
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_IS_ACTIVE)
            .remove(KEY_ACTIVATED_AT)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_DURATION_MINUTES)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "niqob_parent_override_prefs"

        private const val KEY_IS_ACTIVE = "key_override_is_active"
        private const val KEY_ACTIVATED_AT = "key_override_activated_at"
        private const val KEY_EXPIRES_AT = "key_override_expires_at"
        private const val KEY_DURATION_MINUTES = "key_override_duration_minutes"

        @Volatile
        private var instance: SharedPreferencesParentOverrideSessionStore? = null

        fun getInstance(context: Context): SharedPreferencesParentOverrideSessionStore {
            return instance ?: synchronized(this) {
                instance ?: SharedPreferencesParentOverrideSessionStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }

        fun resetForTesting() {
            instance = null
        }
    }
}

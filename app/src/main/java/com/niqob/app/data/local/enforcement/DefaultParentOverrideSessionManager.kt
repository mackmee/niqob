package com.niqob.app.data.local.enforcement

import android.content.Context
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.domain.enforcement.AttemptCheckResult
import com.niqob.app.domain.enforcement.OverrideAttemptResult
import com.niqob.app.domain.enforcement.ParentOverrideAttemptTracker
import com.niqob.app.domain.enforcement.ParentOverrideAuthenticator
import com.niqob.app.domain.enforcement.ParentOverrideSessionManager
import com.niqob.app.domain.enforcement.ParentOverrideSessionStore
import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository

/**
 * Default implementation of [ParentOverrideSessionManager].
 *
 * Coordinates:
 * 1. [ParentOverrideAttemptTracker] for rate limiting (cooldown / lockout).
 * 2. [ParentOverrideAuthenticator] for PIN verification.
 * 3. [ParentOverrideSessionStore] for persistent storage across app lifecycle.
 * 4. [ScreenTimeEnforcementRepository] for reactive in-memory enforcement state.
 */
class DefaultParentOverrideSessionManager(
    private val authenticator: ParentOverrideAuthenticator = LocalParentOverrideAuthenticator(),
    private val sessionStore: ParentOverrideSessionStore,
    private val attemptTracker: ParentOverrideAttemptTracker = ParentOverrideAttemptTracker(),
    private val enforcementRepository: ScreenTimeEnforcementRepository = LocalScreenTimeEnforcementRepository.getInstance(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ParentOverrideSessionManager {

    override suspend fun attemptOverride(
        pin: String,
        durationMinutes: Int,
        currentTimeMillis: Long
    ): OverrideAttemptResult {
        // 1. Check if rate-limiting blocks this attempt before checking credential
        when (val check = attemptTracker.checkCanAttempt(currentTimeMillis)) {
            is AttemptCheckResult.InLockout -> {
                return OverrideAttemptResult.LockoutActive(check.remainingMinutes)
            }
            is AttemptCheckResult.InCooldown -> {
                return OverrideAttemptResult.CooldownActive(check.remainingSeconds)
            }
            is AttemptCheckResult.Allowed -> { /* Proceed */ }
        }

        // 2. Perform authentication verification
        val isPinValid = authenticator.authenticate(pin)

        return if (isPinValid) {
            // Success: reset rate-limiting tracker
            attemptTracker.recordSuccessfulAttempt()

            // Create and persist new time-bounded session
            val session = ParentOverrideSession.create(
                durationMinutes = durationMinutes,
                activatedAtMillis = currentTimeMillis
            )
            sessionStore.save(session)
            enforcementRepository.setParentOverrideSession(session)

            OverrideAttemptResult.Success(session)
        } else {
            // Failed: record attempt and evaluate throttling threshold
            when (val check = attemptTracker.recordFailedAttempt(currentTimeMillis)) {
                is AttemptCheckResult.InLockout -> {
                    OverrideAttemptResult.LockoutActive(check.remainingMinutes)
                }
                is AttemptCheckResult.InCooldown -> {
                    OverrideAttemptResult.CooldownActive(check.remainingSeconds)
                }
                is AttemptCheckResult.Allowed -> {
                    val remaining = if (attemptTracker.getFailedAttempts() < ParentOverrideAttemptTracker.COOLDOWN_THRESHOLD) {
                        ParentOverrideAttemptTracker.COOLDOWN_THRESHOLD - attemptTracker.getFailedAttempts()
                    } else {
                        ParentOverrideAttemptTracker.LOCKOUT_THRESHOLD - attemptTracker.getFailedAttempts()
                    }.coerceAtLeast(1)
                    OverrideAttemptResult.InvalidPin(remaining)
                }
            }
        }
    }

    override fun getActiveSession(currentTimeMillis: Long): ParentOverrideSession? {
        val session = sessionStore.load(currentTimeMillis)
        return if (session != null && session.isCurrentlyActive(currentTimeMillis)) {
            session
        } else {
            null
        }
    }

    override fun clearOverride() {
        sessionStore.clear()
        enforcementRepository.setParentOverrideSession(null)
    }

    companion object {
        @Volatile
        private var instance: DefaultParentOverrideSessionManager? = null

        fun getInstance(context: Context): DefaultParentOverrideSessionManager {
            return instance ?: synchronized(this) {
                instance ?: DefaultParentOverrideSessionManager(
                    authenticator = LocalParentOverrideAuthenticator(),
                    sessionStore = SharedPreferencesParentOverrideSessionStore.getInstance(context),
                    attemptTracker = ParentOverrideAttemptTracker(),
                    enforcementRepository = LocalScreenTimeEnforcementRepository.getInstance()
                ).also { instance = it }
            }
        }

        fun resetForTesting() {
            instance = null
        }
    }
}

package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.ParentOverrideSession

/**
 * Result of an override authentication attempt.
 */
sealed class OverrideAttemptResult {
    /**
     * PIN was verified successfully and a new [ParentOverrideSession] was activated.
     */
    data class Success(val session: ParentOverrideSession) : OverrideAttemptResult()

    /**
     * PIN was incorrect. Provides the number of remaining attempts before rate-limiting applies.
     */
    data class InvalidPin(val remainingAttemptsBeforeThrottling: Int) : OverrideAttemptResult()

    /**
     * Rate-limiting cooldown is currently active (after 3 consecutive failed attempts).
     */
    data class CooldownActive(val remainingSeconds: Int) : OverrideAttemptResult()

    /**
     * Security lockout is currently active (after 5 consecutive failed attempts).
     */
    data class LockoutActive(val remainingMinutes: Int) : OverrideAttemptResult()
}

/**
 * Orchestrator separating authentication verification, brute-force rate-limiting,
 * session persistence, and repository enforcement state.
 */
interface ParentOverrideSessionManager {

    /**
     * Attempts to unlock screen-time enforcement using the provided parent PIN.
     *
     * @param pin The parent-entered PIN.
     * @param durationMinutes The duration of the session in minutes (defaults to 15m).
     * @param currentTimeMillis The timestamp of the attempt.
     */
    suspend fun attemptOverride(
        pin: String,
        durationMinutes: Int = ParentOverrideSession.DEFAULT_DURATION_MINUTES,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): OverrideAttemptResult

    /**
     * Retrieves the currently active override session, if one exists and has not expired.
     */
    fun getActiveSession(currentTimeMillis: Long = System.currentTimeMillis()): ParentOverrideSession?

    /**
     * Explicitly revokes and clears any active override session.
     */
    fun clearOverride()
}

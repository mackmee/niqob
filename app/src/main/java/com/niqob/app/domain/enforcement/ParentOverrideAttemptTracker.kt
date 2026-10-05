package com.niqob.app.domain.enforcement

/**
 * Result of checking whether a parent PIN attempt is currently permissible.
 */
sealed class AttemptCheckResult {
    data object Allowed : AttemptCheckResult()
    data class InCooldown(val remainingSeconds: Int, val cooldownUntilMillis: Long) : AttemptCheckResult()
    data class InLockout(val remainingMinutes: Int, val lockoutUntilMillis: Long) : AttemptCheckResult()
}

/**
 * Pure domain attempt policy and rate-limiting tracker for Parent PIN overrides.
 *
 * Requirements:
 * - Tracks consecutive failed attempts.
 * - After 3 consecutive failed attempts: requires a 60-second cooldown.
 * - After 5 consecutive failed attempts: requires a 15-minute lockout.
 * - Successful authentication resets the counter to 0.
 * - During cooldown or lockout, attempts are rejected immediately without checking credentials.
 * - Does NOT lock the entire device or disable NIQOB.
 */
class ParentOverrideAttemptTracker(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var failedAttempts: Int = 0

    @Volatile
    private var cooldownUntilMillis: Long = 0L

    @Volatile
    private var lockoutUntilMillis: Long = 0L

    fun getFailedAttempts(): Int = failedAttempts

    /**
     * Checks if an attempt is currently permitted under rate-limiting policy.
     */
    @Synchronized
    fun checkCanAttempt(currentTimeMillis: Long = timeProvider()): AttemptCheckResult {
        // Priority 1: Check 15-minute lockout
        if (currentTimeMillis < lockoutUntilMillis) {
            val remMillis = lockoutUntilMillis - currentTimeMillis
            val remMinutes = ((remMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            return AttemptCheckResult.InLockout(remMinutes, lockoutUntilMillis)
        }

        // Priority 2: Check 60-second cooldown
        if (currentTimeMillis < cooldownUntilMillis) {
            val remMillis = cooldownUntilMillis - currentTimeMillis
            val remSeconds = ((remMillis + 999L) / 1_000L).toInt().coerceAtLeast(1)
            return AttemptCheckResult.InCooldown(remSeconds, cooldownUntilMillis)
        }

        return AttemptCheckResult.Allowed
    }

    /**
     * Records a failed authentication attempt and updates cooldown/lockout thresholds.
     */
    @Synchronized
    fun recordFailedAttempt(currentTimeMillis: Long = timeProvider()): AttemptCheckResult {
        failedAttempts++

        if (failedAttempts >= LOCKOUT_THRESHOLD) {
            lockoutUntilMillis = currentTimeMillis + LOCKOUT_DURATION_MILLIS
            cooldownUntilMillis = 0L
            val remMinutes = (LOCKOUT_DURATION_MILLIS / 60_000L).toInt()
            return AttemptCheckResult.InLockout(remMinutes, lockoutUntilMillis)
        } else if (failedAttempts == COOLDOWN_THRESHOLD) {
            cooldownUntilMillis = currentTimeMillis + COOLDOWN_DURATION_MILLIS
            val remSeconds = (COOLDOWN_DURATION_MILLIS / 1_000L).toInt()
            return AttemptCheckResult.InCooldown(remSeconds, cooldownUntilMillis)
        }

        return AttemptCheckResult.Allowed
    }

    /**
     * Resets failed attempts and unlocks all rate limiting upon successful authentication.
     */
    @Synchronized
    fun recordSuccessfulAttempt() {
        failedAttempts = 0
        cooldownUntilMillis = 0L
        lockoutUntilMillis = 0L
    }

    /**
     * Resets state for testing purposes.
     */
    @Synchronized
    fun resetForTesting() {
        failedAttempts = 0
        cooldownUntilMillis = 0L
        lockoutUntilMillis = 0L
    }

    companion object {
        const val COOLDOWN_THRESHOLD = 3
        const val LOCKOUT_THRESHOLD = 5

        const val COOLDOWN_DURATION_MILLIS = 60 * 1000L       // 60 seconds
        const val LOCKOUT_DURATION_MILLIS = 15 * 60 * 1000L   // 15 minutes
    }
}

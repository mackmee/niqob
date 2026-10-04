package com.niqob.app.domain.enforcement

/**
 * Thread-safe monotonic debounce tracker to prevent repetitive enforcement actions
 * or rapid activity relaunch loops for blocked packages.
 *
 * @param cooldownDurationMillis Minimum elapsed time before the same package can trigger enforcement again.
 * @param timeProvider Monotonic time provider (e.g. SystemClock.elapsedRealtime in Android, testable in JVM).
 */
class EnforcementDebouncer(
    private val cooldownDurationMillis: Long = DEFAULT_COOLDOWN_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val lastEnforcementTimes = mutableMapOf<String, Long>()

    /**
     * Determines whether enforcement action should be executed for [packageName].
     * If permitted, records the timestamp and returns true.
     * If within the cooldown window, returns false.
     */
    fun shouldEnforce(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        synchronized(lock) {
            val now = timeProvider()
            val lastTime = lastEnforcementTimes[packageName]
            if (lastTime == null || now - lastTime >= cooldownDurationMillis) {
                lastEnforcementTimes[packageName] = now
                return true
            }
            return false
        }
    }

    /**
     * Resets debounce state for all packages.
     */
    fun reset() {
        synchronized(lock) {
            lastEnforcementTimes.clear()
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1500L
    }
}

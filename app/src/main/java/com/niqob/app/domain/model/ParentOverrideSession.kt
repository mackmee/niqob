package com.niqob.app.domain.model

/**
 * Domain model representing a time-bounded parent override session.
 *
 * Replaces boolean-only override with an authoritative, timestamped session.
 * Expiration is strictly evaluated against current monotonic/wall-clock time:
 * if `currentTimeMillis >= expiresAtMillis`, the session is expired regardless
 * of process recreation or stored state.
 */
data class ParentOverrideSession(
    val isActive: Boolean = true,
    val activatedAtMillis: Long,
    val expiresAtMillis: Long,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES
) {
    /**
     * Returns true if current time has reached or exceeded the expiration timestamp.
     */
    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return currentTimeMillis >= expiresAtMillis
    }

    /**
     * Returns the remaining duration in milliseconds, clamped to 0.
     */
    fun remainingMillis(currentTimeMillis: Long = System.currentTimeMillis()): Long {
        return (expiresAtMillis - currentTimeMillis).coerceAtLeast(0L)
    }

    /**
     * Returns the remaining duration in full minutes (ceiling rounded), clamped to 0.
     */
    fun remainingMinutes(currentTimeMillis: Long = System.currentTimeMillis()): Int {
        val remaining = remainingMillis(currentTimeMillis)
        return ((remaining + 59_999L) / 60_000L).toInt()
    }

    /**
     * Returns true only when the session is flagged active AND has not yet expired.
     */
    fun isCurrentlyActive(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return isActive && !isExpired(currentTimeMillis)
    }

    companion object {
        const val DEFAULT_DURATION_MINUTES = 15
        val SUPPORTED_DURATIONS_MINUTES = listOf(15, 30, 60)

        /**
         * Factory method to create a new time-bounded session from a duration in minutes.
         */
        fun create(
            durationMinutes: Int = DEFAULT_DURATION_MINUTES,
            activatedAtMillis: Long = System.currentTimeMillis()
        ): ParentOverrideSession {
            val validDuration = if (durationMinutes in SUPPORTED_DURATIONS_MINUTES) {
                durationMinutes
            } else {
                DEFAULT_DURATION_MINUTES
            }
            val expiresAt = activatedAtMillis + (validDuration * 60 * 1000L)
            return ParentOverrideSession(
                isActive = true,
                activatedAtMillis = activatedAtMillis,
                expiresAtMillis = expiresAt,
                durationMinutes = validDuration
            )
        }
    }
}

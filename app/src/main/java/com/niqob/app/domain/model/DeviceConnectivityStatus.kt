package com.niqob.app.domain.model

/**
 * High-level connectivity status calculated from the device's last heartbeat timestamp.
 *
 * Threshold definitions:
 * - ONLINE: Heartbeat received within the last 2 minutes (120,000 ms).
 * - RECENTLY_ONLINE: Heartbeat received between 2 minutes and 15 minutes ago (900,000 ms).
 * - OFFLINE: No heartbeat for more than 15 minutes, or device has never been seen.
 */
enum class DeviceConnectivityStatus {
    ONLINE,
    RECENTLY_ONLINE,
    OFFLINE;

    companion object {
        const val ONLINE_THRESHOLD_MS: Long = 2 * 60 * 1000L // 2 minutes
        const val RECENTLY_ONLINE_THRESHOLD_MS: Long = 15 * 60 * 1000L // 15 minutes

        fun calculate(
            lastSeenAtMillis: Long,
            currentMillis: Long = System.currentTimeMillis()
        ): DeviceConnectivityStatus {
            if (lastSeenAtMillis <= 0L) return OFFLINE
            val elapsed = currentMillis - lastSeenAtMillis
            if (elapsed < 0L) return ONLINE // Guard against minor device clock drifts
            return when {
                elapsed <= ONLINE_THRESHOLD_MS -> ONLINE
                elapsed <= RECENTLY_ONLINE_THRESHOLD_MS -> RECENTLY_ONLINE
                else -> OFFLINE
            }
        }
    }
}

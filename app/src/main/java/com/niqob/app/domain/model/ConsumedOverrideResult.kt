package com.niqob.app.domain.model

import java.time.Instant

/**
 * Domain model representing the server-authoritative result of consuming
 * an approved screen-time override request.
 *
 * Preserves the exact server-calculated [consumedAt] and [consumedExpiresAt]
 * timestamps, preventing arbitrary client expiration overrides.
 */
data class ConsumedOverrideResult(
    val requestId: String,
    val status: RemoteOverrideRequestStatus = RemoteOverrideRequestStatus.CONSUMED,
    val approvedDurationMinutes: Int,
    val consumedAt: Instant,
    val consumedExpiresAt: Instant
) {
    val consumedAtMillis: Long get() = consumedAt.toEpochMilli()
    val consumedExpiresAtMillis: Long get() = consumedExpiresAt.toEpochMilli()
}

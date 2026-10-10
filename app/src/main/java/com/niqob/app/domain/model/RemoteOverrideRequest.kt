package com.niqob.app.domain.model

import java.time.Instant

/**
 * Domain model representing a remote screen-time override request.
 *
 * Encapsulates the complete parent-child request, approval, consumption,
 * and audit lifecycle without exposing transport DTO details.
 */
data class RemoteOverrideRequest(
    val id: String,
    val familyId: String? = null,
    val deviceId: String,
    val requestedDurationMinutes: Int,
    val approvedDurationMinutes: Int? = null,
    val status: RemoteOverrideRequestStatus,
    val reason: String? = null,
    val rejectionReason: String? = null,
    val createdAt: Instant,
    val claimExpiresAt: Instant? = null,
    val decidedAt: Instant? = null,
    val decidedBy: String? = null,
    val consumedAt: Instant? = null,
    val consumedExpiresAt: Instant? = null,
    val revokedAt: Instant? = null,
    val revokedBy: String? = null,
    val isActive: Boolean = false,
    val isExpired: Boolean = false,
    val remainingSeconds: Long = 0L
) {
    val createdAtMillis: Long get() = createdAt.toEpochMilli()
    val claimExpiresAtMillis: Long? get() = claimExpiresAt?.toEpochMilli()
    val decidedAtMillis: Long? get() = decidedAt?.toEpochMilli()
    val consumedAtMillis: Long? get() = consumedAt?.toEpochMilli()
    val consumedExpiresAtMillis: Long? get() = consumedExpiresAt?.toEpochMilli()
    val revokedAtMillis: Long? get() = revokedAt?.toEpochMilli()

    companion object {
        const val MAX_REASON_LENGTH = 200
        val VALID_DURATIONS_MINUTES = setOf(15, 30, 60)
    }
}

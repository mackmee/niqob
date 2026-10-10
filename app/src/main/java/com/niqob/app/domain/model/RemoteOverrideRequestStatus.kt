package com.niqob.app.domain.model

/**
 * Domain status enum representing the lifecycle state of a remote screen-time override request.
 *
 * State transitions:
 * - PENDING -> APPROVED | REJECTED | EXPIRED
 * - APPROVED -> CONSUMED | EXPIRED | REVOKED
 * - CONSUMED -> REVOKED (when active)
 * - EXPIRED / REJECTED -> Terminal
 * - CONSUMED (naturally expired) -> Preserved permanently for audit fidelity
 */
enum class RemoteOverrideRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED,
    CONSUMED;

    companion object {
        fun fromString(value: String?): RemoteOverrideRequestStatus {
            return entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

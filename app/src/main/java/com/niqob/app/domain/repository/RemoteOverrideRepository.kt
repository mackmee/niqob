package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ConsumedOverrideResult
import com.niqob.app.domain.model.RemoteOverrideRequest

/**
 * Domain repository abstraction for remote parent screen-time override operations.
 *
 * Enforces strict role boundaries:
 * - Child operations ([createRequest], [getRequestStatus], [consumeOverride]) rely on device ID
 *   and active pairing session authorization.
 * - Parent operations ([getPendingRequests], [approveRequest], [rejectRequest], [revokeOverride])
 *   rely on authenticated parent family ownership.
 */
interface RemoteOverrideRepository {

    /**
     * Creates a new pending override request for a paired child device.
     *
     * @param deviceId The child's paired device UUID.
     * @param requestedDurationMinutes Duration in minutes (must be 15, 30, or 60).
     * @param reason Optional reason text (max 200 characters).
     */
    suspend fun createRequest(
        deviceId: String,
        requestedDurationMinutes: Int,
        reason: String? = null,
        deviceSecret: String? = null
    ): Result<RemoteOverrideRequest>

    /**
     * Checks the current status and derived timer states of an override request.
     *
     * @param deviceId The child device UUID to which the request must belong.
     * @param requestId The override request UUID.
     * @param deviceSecret Optional child device secret token.
     */
    suspend fun getRequestStatus(
        deviceId: String,
        requestId: String,
        deviceSecret: String? = null
    ): Result<RemoteOverrideRequest>

    /**
     * Consumes an approved override request, transitioning it to CONSUMED and
     * activating the server-authoritative time-bounded window.
     *
     * @param deviceId The child device UUID.
     * @param requestId The override request UUID.
     * @param deviceSecret Optional child device secret token.
     */
    suspend fun consumeOverride(
        deviceId: String,
        requestId: String,
        deviceSecret: String? = null
    ): Result<ConsumedOverrideResult>

    /**
     * Retrieves all active pending override requests for the authenticated parent's family,
     * optionally filtered by a specific child device.
     *
     * @param deviceId Optional child device UUID filter.
     */
    suspend fun getPendingRequests(
        deviceId: String? = null
    ): Result<List<RemoteOverrideRequest>>

    /**
     * Approves a pending override request, optionally overriding the child's requested duration.
     *
     * @param requestId The override request UUID.
     * @param approvedDurationMinutes Optional approved duration (15, 30, or 60). If null, defaults
     *                                to requested duration.
     */
    suspend fun approveRequest(
        requestId: String,
        approvedDurationMinutes: Int? = null
    ): Result<RemoteOverrideRequest>

    /**
     * Rejects a pending override request with an optional rejection reason.
     *
     * @param requestId The override request UUID.
     * @param rejectionReason Optional rejection note (max 200 characters).
     */
    suspend fun rejectRequest(
        requestId: String,
        rejectionReason: String? = null
    ): Result<RemoteOverrideRequest>

    /**
     * Revokes an approved or currently active consumed override request.
     *
     * @param requestId The override request UUID.
     */
    suspend fun revokeOverride(
        requestId: String
    ): Result<RemoteOverrideRequest>
}

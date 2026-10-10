package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ConsumedOverrideResult
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import com.niqob.app.domain.repository.RemoteOverrideRepository
import java.time.Instant
import java.util.UUID

class FakeRemoteOverrideRepository(
    private val clock: () -> Instant = { Instant.now() }
) : RemoteOverrideRepository {

    private val storage = mutableMapOf<String, RemoteOverrideRequest>()
    var shouldFail: Boolean = false
    var failureException: Exception = Exception("Simulated network failure")

    // Call tracking
    var createCallCount: Int = 0
    var consumeCallCount: Int = 0
    var approveCallCount: Int = 0
    var rejectCallCount: Int = 0
    var revokeCallCount: Int = 0
    var statusCallCount: Int = 0
    var getPendingCallCount: Int = 0

    var lastCreatedDeviceId: String? = null
    var lastCreatedDeviceSecret: String? = null
    var lastRequestedDuration: Int? = null
    var lastReason: String? = null

    var lastConsumedDeviceId: String? = null
    var lastConsumedRequestId: String? = null
    var lastConsumedDeviceSecret: String? = null

    var lastStatusDeviceId: String? = null
    var lastStatusRequestId: String? = null
    var lastStatusDeviceSecret: String? = null

    var lastApprovedRequestId: String? = null
    var lastApprovedDuration: Int? = null

    var lastRejectedRequestId: String? = null
    var lastRejectionReason: String? = null

    var lastRevokedRequestId: String? = null

    override suspend fun createRequest(
        deviceId: String,
        requestedDurationMinutes: Int,
        reason: String?,
        deviceSecret: String?
    ): Result<RemoteOverrideRequest> {
        createCallCount++
        lastCreatedDeviceId = deviceId
        lastCreatedDeviceSecret = deviceSecret
        lastRequestedDuration = requestedDurationMinutes
        lastReason = reason

        if (shouldFail) return Result.Error(failureException)
        if (deviceId.isBlank()) return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        if (requestedDurationMinutes !in RemoteOverrideRequest.VALID_DURATIONS_MINUTES) {
            return Result.Error(IllegalArgumentException("Requested duration must be 15, 30, or 60 minutes."))
        }
        if (reason != null && reason.length > RemoteOverrideRequest.MAX_REASON_LENGTH) {
            return Result.Error(IllegalArgumentException("Reason cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters."))
        }

        // Check if active pending exists
        val hasPending = storage.values.any { it.deviceId == deviceId && it.status == RemoteOverrideRequestStatus.PENDING }
        if (hasPending) {
            return Result.Error(IllegalStateException("A pending override request already exists for this device."))
        }

        val now = clock()
        val requestId = UUID.randomUUID().toString()
        val request = RemoteOverrideRequest(
            id = requestId,
            familyId = "family-test-1",
            deviceId = deviceId,
            requestedDurationMinutes = requestedDurationMinutes,
            status = RemoteOverrideRequestStatus.PENDING,
            reason = reason,
            createdAt = now,
            claimExpiresAt = now.plusSeconds(15 * 60)
        )
        storage[requestId] = request
        return Result.Success(request)
    }

    override suspend fun getRequestStatus(
        deviceId: String,
        requestId: String,
        deviceSecret: String?
    ): Result<RemoteOverrideRequest> {
        statusCallCount++
        lastStatusDeviceId = deviceId
        lastStatusRequestId = requestId
        lastStatusDeviceSecret = deviceSecret

        if (shouldFail) return Result.Error(failureException)
        if (deviceId.isBlank()) return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        if (requestId.isBlank()) return Result.Error(IllegalArgumentException("Request ID cannot be blank."))

        val request = storage[requestId]
            ?: return Result.Error(NoSuchElementException("Override request not found."))
        if (request.deviceId != deviceId) {
            return Result.Error(SecurityException("Request does not belong to specified device."))
        }

        return Result.Success(request)
    }

    override suspend fun consumeOverride(
        deviceId: String,
        requestId: String,
        deviceSecret: String?
    ): Result<ConsumedOverrideResult> {
        consumeCallCount++
        lastConsumedDeviceId = deviceId
        lastConsumedRequestId = requestId
        lastConsumedDeviceSecret = deviceSecret

        if (shouldFail) return Result.Error(failureException)
        if (deviceId.isBlank()) return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        if (requestId.isBlank()) return Result.Error(IllegalArgumentException("Request ID cannot be blank."))

        val request = storage[requestId]
            ?: return Result.Error(NoSuchElementException("Override request not found."))
        if (request.deviceId != deviceId) {
            return Result.Error(SecurityException("Request does not belong to specified device."))
        }
        if (request.status == RemoteOverrideRequestStatus.CONSUMED) {
            return Result.Error(IllegalStateException("This override has already been consumed."))
        }
        if (request.status != RemoteOverrideRequestStatus.APPROVED) {
            return Result.Error(IllegalStateException("Override request is not approved (current status: ${request.status})."))
        }

        val now = clock()
        val duration = request.approvedDurationMinutes ?: request.requestedDurationMinutes
        val expiresAt = now.plusSeconds((duration * 60).toLong())

        val updated = request.copy(
            status = RemoteOverrideRequestStatus.CONSUMED,
            consumedAt = now,
            consumedExpiresAt = expiresAt,
            isActive = true,
            isExpired = false,
            remainingSeconds = (duration * 60).toLong()
        )
        storage[requestId] = updated

        return Result.Success(
            ConsumedOverrideResult(
                requestId = requestId,
                status = RemoteOverrideRequestStatus.CONSUMED,
                approvedDurationMinutes = duration,
                consumedAt = now,
                consumedExpiresAt = expiresAt
            )
        )
    }

    override suspend fun getPendingRequests(
        deviceId: String?
    ): Result<List<RemoteOverrideRequest>> {
        getPendingCallCount++
        if (shouldFail) return Result.Error(failureException)
        if (deviceId != null && deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID filter cannot be blank."))
        }

        val list = storage.values
            .filter { it.status == RemoteOverrideRequestStatus.PENDING }
            .filter { deviceId == null || it.deviceId == deviceId }
            .sortedBy { it.createdAt }

        return Result.Success(list)
    }

    override suspend fun approveRequest(
        requestId: String,
        approvedDurationMinutes: Int?
    ): Result<RemoteOverrideRequest> {
        approveCallCount++
        lastApprovedRequestId = requestId
        lastApprovedDuration = approvedDurationMinutes

        if (shouldFail) return Result.Error(failureException)
        if (requestId.isBlank()) return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        if (approvedDurationMinutes != null && approvedDurationMinutes !in RemoteOverrideRequest.VALID_DURATIONS_MINUTES) {
            return Result.Error(IllegalArgumentException("Approved duration must be 15, 30, or 60 minutes."))
        }

        val request = storage[requestId]
            ?: return Result.Error(NoSuchElementException("Override request not found."))
        if (request.status != RemoteOverrideRequestStatus.PENDING) {
            return Result.Error(IllegalStateException("Request is not pending (current status: ${request.status})."))
        }

        val now = clock()
        val duration = approvedDurationMinutes ?: request.requestedDurationMinutes
        val updated = request.copy(
            status = RemoteOverrideRequestStatus.APPROVED,
            approvedDurationMinutes = duration,
            decidedAt = now,
            claimExpiresAt = now.plusSeconds(15 * 60)
        )
        storage[requestId] = updated
        return Result.Success(updated)
    }

    override suspend fun rejectRequest(
        requestId: String,
        rejectionReason: String?
    ): Result<RemoteOverrideRequest> {
        rejectCallCount++
        lastRejectedRequestId = requestId
        lastRejectionReason = rejectionReason

        if (shouldFail) return Result.Error(failureException)
        if (requestId.isBlank()) return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        if (rejectionReason != null && rejectionReason.length > RemoteOverrideRequest.MAX_REASON_LENGTH) {
            return Result.Error(IllegalArgumentException("Rejection reason cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters."))
        }

        val request = storage[requestId]
            ?: return Result.Error(NoSuchElementException("Override request not found."))
        if (request.status != RemoteOverrideRequestStatus.PENDING) {
            return Result.Error(IllegalStateException("Request is not pending (current status: ${request.status})."))
        }

        val now = clock()
        val updated = request.copy(
            status = RemoteOverrideRequestStatus.REJECTED,
            rejectionReason = rejectionReason,
            decidedAt = now
        )
        storage[requestId] = updated
        return Result.Success(updated)
    }

    override suspend fun revokeOverride(
        requestId: String
    ): Result<RemoteOverrideRequest> {
        revokeCallCount++
        lastRevokedRequestId = requestId

        if (shouldFail) return Result.Error(failureException)
        if (requestId.isBlank()) return Result.Error(IllegalArgumentException("Request ID cannot be blank."))

        val request = storage[requestId]
            ?: return Result.Error(NoSuchElementException("Override request not found."))
        if (request.status == RemoteOverrideRequestStatus.REVOKED) {
            return Result.Error(IllegalStateException("Override is already revoked."))
        }
        if (request.status != RemoteOverrideRequestStatus.APPROVED && request.status != RemoteOverrideRequestStatus.CONSUMED) {
            return Result.Error(IllegalStateException("Only APPROVED or active CONSUMED overrides can be revoked."))
        }

        val now = clock()
        val updated = request.copy(
            status = RemoteOverrideRequestStatus.REVOKED,
            revokedAt = now,
            isActive = false
        )
        storage[requestId] = updated
        return Result.Success(updated)
    }

    fun seedRequest(request: RemoteOverrideRequest) {
        storage[request.id] = request
    }

    fun getStoredRequest(requestId: String): RemoteOverrideRequest? = storage[requestId]

    fun getStoredCount(): Int = storage.size

    fun clear() {
        storage.clear()
        shouldFail = false
        createCallCount = 0
        consumeCallCount = 0
        approveCallCount = 0
        rejectCallCount = 0
        revokeCallCount = 0
        statusCallCount = 0
        getPendingCallCount = 0
        lastCreatedDeviceId = null
        lastRequestedDuration = null
        lastReason = null
        lastConsumedDeviceId = null
        lastConsumedRequestId = null
        lastApprovedRequestId = null
        lastApprovedDuration = null
        lastRejectedRequestId = null
        lastRejectionReason = null
        lastRevokedRequestId = null
    }
}

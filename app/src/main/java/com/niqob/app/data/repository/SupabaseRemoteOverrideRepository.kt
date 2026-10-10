package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.data.remote.supabase.dto.ApproveOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.ConsumeOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.CreateOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.GetPendingOverridesResponseDto
import com.niqob.app.data.remote.supabase.dto.OverrideStatusResponseDto
import com.niqob.app.data.remote.supabase.dto.PendingOverrideItemDto
import com.niqob.app.data.remote.supabase.dto.RejectOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.RevokeOverrideResponseDto
import com.niqob.app.domain.model.ConsumedOverrideResult
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import com.niqob.app.domain.repository.RemoteOverrideRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Supabase PostgREST RPC implementation of [RemoteOverrideRepository].
 *
 * All state modifications and queries are mediated strictly through the SECURITY DEFINER
 * stored procedures deployed in NIQOB Phase 4F-2 and hardened with cryptographic
 * child-device credentials in Phase 5 Step 5-4.
 */
class SupabaseRemoteOverrideRepository(
    private val clientProvider: () -> SupabaseClient = { NiqobSupabaseClient.getClient() },
    private val isConfiguredProvider: () -> Boolean = { NiqobSupabaseClient.isConfigured },
    private val credentialStore: ChildDeviceCredentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance(),
    private val clock: () -> Instant = { Instant.now() }
) : RemoteOverrideRepository {

    override suspend fun createRequest(
        deviceId: String,
        requestedDurationMinutes: Int,
        reason: String?,
        deviceSecret: String?
    ): Result<RemoteOverrideRequest> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (requestedDurationMinutes !in RemoteOverrideRequest.VALID_DURATIONS_MINUTES) {
            return Result.Error(
                IllegalArgumentException("Requested duration must be 15, 30, or 60 minutes.")
            )
        }
        if (reason != null && reason.length > RemoteOverrideRequest.MAX_REASON_LENGTH) {
            return Result.Error(
                IllegalArgumentException("Reason cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters.")
            )
        }

        // Retrieve cryptographic device credential
        val secret = (deviceSecret ?: credentialStore.getDeviceSecret())?.trim()
        if (secret.isNullOrBlank()) {
            return Result.Error(
                IllegalStateException("Device authentication credential not found. Please pair the device again.")
            )
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "create_screen_time_override_request",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId.trim())
                    put("p_requested_duration_minutes", requestedDurationMinutes)
                    reason?.let { put("p_reason", it.trim()) }
                    put("p_device_secret", secret)
                }
            ).decodeAs<CreateOverrideResponseDto>()

            if (response.success && !response.requestId.isNullOrBlank()) {
                val claimExpiresAt = parseIsoInstant(response.claimExpiresAt)
                Result.Success(
                    RemoteOverrideRequest(
                        id = response.requestId,
                        deviceId = deviceId.trim(),
                        requestedDurationMinutes = requestedDurationMinutes,
                        status = RemoteOverrideRequestStatus.fromString(response.status),
                        reason = reason?.trim(),
                        createdAt = clock(),
                        claimExpiresAt = claimExpiresAt
                    )
                )
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to create screen time override request.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error creating override request: ${e.message}"))
        }
    }

    override suspend fun getRequestStatus(
        deviceId: String,
        requestId: String,
        deviceSecret: String?
    ): Result<RemoteOverrideRequest> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (requestId.isBlank()) {
            return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        val secret = (deviceSecret ?: credentialStore.getDeviceSecret())?.trim()

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "get_override_request_status",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId.trim())
                    put("p_request_id", requestId.trim())
                    secret?.let { put("p_device_secret", it) }
                }
            ).decodeAs<OverrideStatusResponseDto>()

            if (response.success && !response.requestId.isNullOrBlank()) {
                Result.Success(mapStatusDtoToDomain(response, clock()))
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to fetch override request status.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error fetching override request status: ${e.message}"))
        }
    }

    override suspend fun consumeOverride(
        deviceId: String,
        requestId: String,
        deviceSecret: String?
    ): Result<ConsumedOverrideResult> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (requestId.isBlank()) {
            return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        }

        val secret = (deviceSecret ?: credentialStore.getDeviceSecret())?.trim()
        if (secret.isNullOrBlank()) {
            return Result.Error(
                IllegalStateException("Device authentication credential not found. Please pair the device again.")
            )
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "consume_screen_time_override",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId.trim())
                    put("p_request_id", requestId.trim())
                    put("p_device_secret", secret)
                }
            ).decodeAs<ConsumeOverrideResponseDto>()

            if (response.success && !response.requestId.isNullOrBlank()) {
                val consumedAt = parseIsoInstant(response.consumedAt) ?: clock()
                val consumedExpiresAt = parseIsoInstant(response.consumedExpiresAt)
                    ?: consumedAt.plusSeconds(((response.approvedDurationMinutes ?: 15) * 60).toLong())

                Result.Success(
                    ConsumedOverrideResult(
                        requestId = response.requestId,
                        status = RemoteOverrideRequestStatus.fromString(response.status),
                        approvedDurationMinutes = response.approvedDurationMinutes ?: 15,
                        consumedAt = consumedAt,
                        consumedExpiresAt = consumedExpiresAt
                    )
                )
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to consume override request.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error consuming override request: ${e.message}"))
        }
    }

    override suspend fun getPendingRequests(
        deviceId: String?
    ): Result<List<RemoteOverrideRequest>> {
        if (deviceId != null && deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID filter cannot be blank."))
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "get_pending_override_requests",
                parameters = buildJsonObject {
                    deviceId?.let { put("p_device_id", it.trim()) }
                }
            ).decodeAs<GetPendingOverridesResponseDto>()

            if (response.success) {
                val now = clock()
                val domainList = response.requests.map { mapPendingItemToDomain(it, now) }
                Result.Success(domainList)
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to fetch pending override requests.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error fetching pending override requests: ${e.message}"))
        }
    }

    override suspend fun approveRequest(
        requestId: String,
        approvedDurationMinutes: Int?
    ): Result<RemoteOverrideRequest> {
        if (requestId.isBlank()) {
            return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        }
        if (approvedDurationMinutes != null && approvedDurationMinutes !in RemoteOverrideRequest.VALID_DURATIONS_MINUTES) {
            return Result.Error(
                IllegalArgumentException("Approved duration must be 15, 30, or 60 minutes.")
            )
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "approve_screen_time_override_request",
                parameters = buildJsonObject {
                    put("p_request_id", requestId.trim())
                    approvedDurationMinutes?.let { put("p_approved_duration_minutes", it) }
                }
            ).decodeAs<ApproveOverrideResponseDto>()

            if (response.success && !response.requestId.isNullOrBlank()) {
                val decidedAt = parseIsoInstant(response.decidedAt)
                val claimExpiresAt = parseIsoInstant(response.claimExpiresAt)
                Result.Success(
                    RemoteOverrideRequest(
                        id = response.requestId,
                        deviceId = "",
                        requestedDurationMinutes = response.approvedDurationMinutes ?: 15,
                        approvedDurationMinutes = response.approvedDurationMinutes,
                        status = RemoteOverrideRequestStatus.fromString(response.status),
                        decidedAt = decidedAt,
                        claimExpiresAt = claimExpiresAt,
                        createdAt = decidedAt ?: clock()
                    )
                )
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to approve override request.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error approving override request: ${e.message}"))
        }
    }

    override suspend fun rejectRequest(
        requestId: String,
        rejectionReason: String?
    ): Result<RemoteOverrideRequest> {
        if (requestId.isBlank()) {
            return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        }
        if (rejectionReason != null && rejectionReason.length > RemoteOverrideRequest.MAX_REASON_LENGTH) {
            return Result.Error(
                IllegalArgumentException("Rejection reason cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters.")
            )
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "reject_screen_time_override_request",
                parameters = buildJsonObject {
                    put("p_request_id", requestId.trim())
                    rejectionReason?.let { put("p_reason", it.trim()) }
                }
            ).decodeAs<RejectOverrideResponseDto>()

            if (response.success && !response.requestId.isNullOrBlank()) {
                val decidedAt = parseIsoInstant(response.decidedAt)
                Result.Success(
                    RemoteOverrideRequest(
                        id = response.requestId,
                        deviceId = "",
                        requestedDurationMinutes = 15,
                        status = RemoteOverrideRequestStatus.fromString(response.status),
                        rejectionReason = response.rejectionReason,
                        decidedAt = decidedAt,
                        createdAt = decidedAt ?: clock()
                    )
                )
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to reject override request.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error rejecting override request: ${e.message}"))
        }
    }

    override suspend fun revokeOverride(
        requestId: String
    ): Result<RemoteOverrideRequest> {
        if (requestId.isBlank()) {
            return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        }

        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "revoke_screen_time_override",
                parameters = buildJsonObject {
                    put("p_request_id", requestId.trim())
                }
            ).decodeAs<RevokeOverrideResponseDto>()

            if (response.success && !response.requestId.isNullOrBlank()) {
                val revokedAt = parseIsoInstant(response.revokedAt)
                Result.Success(
                    RemoteOverrideRequest(
                        id = response.requestId,
                        deviceId = "",
                        requestedDurationMinutes = 15,
                        status = RemoteOverrideRequestStatus.fromString(response.status),
                        revokedAt = revokedAt,
                        createdAt = revokedAt ?: clock()
                    )
                )
            } else {
                Result.Error(
                    Exception(response.message ?: response.errorCode ?: "Failed to revoke override request.")
                )
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error revoking override request: ${e.message}"))
        }
    }

    companion object {
        fun mapStatusDtoToDomain(dto: OverrideStatusResponseDto, fallbackNow: Instant): RemoteOverrideRequest {
            val createdAt = parseIsoInstant(dto.createdAt) ?: fallbackNow
            return RemoteOverrideRequest(
                id = dto.requestId ?: "",
                deviceId = dto.deviceId ?: "",
                requestedDurationMinutes = dto.requestedDurationMinutes,
                approvedDurationMinutes = dto.approvedDurationMinutes,
                status = RemoteOverrideRequestStatus.fromString(dto.status),
                reason = dto.reason,
                rejectionReason = dto.rejectionReason,
                createdAt = createdAt,
                claimExpiresAt = parseIsoInstant(dto.claimExpiresAt),
                decidedAt = parseIsoInstant(dto.decidedAt),
                consumedAt = parseIsoInstant(dto.consumedAt),
                consumedExpiresAt = parseIsoInstant(dto.consumedExpiresAt),
                isActive = dto.isActive,
                isExpired = dto.isExpired,
                remainingSeconds = dto.remainingSeconds
            )
        }

        fun mapPendingItemToDomain(dto: PendingOverrideItemDto, fallbackNow: Instant): RemoteOverrideRequest {
            val createdAt = parseIsoInstant(dto.createdAt) ?: fallbackNow
            return RemoteOverrideRequest(
                id = dto.requestId,
                deviceId = dto.deviceId,
                requestedDurationMinutes = dto.requestedDurationMinutes,
                approvedDurationMinutes = dto.approvedDurationMinutes,
                status = RemoteOverrideRequestStatus.fromString(dto.status),
                reason = dto.reason,
                rejectionReason = dto.rejectionReason,
                createdAt = createdAt,
                claimExpiresAt = parseIsoInstant(dto.claimExpiresAt),
                decidedAt = parseIsoInstant(dto.decidedAt),
                consumedAt = parseIsoInstant(dto.consumedAt),
                consumedExpiresAt = parseIsoInstant(dto.consumedExpiresAt),
                revokedAt = parseIsoInstant(dto.revokedAt)
            )
        }

        fun parseIsoInstant(isoString: String?): Instant? {
            if (isoString.isNullOrBlank()) return null
            return try {
                OffsetDateTime.parse(isoString).toInstant()
            } catch (_: Exception) {
                try {
                    Instant.parse(isoString)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

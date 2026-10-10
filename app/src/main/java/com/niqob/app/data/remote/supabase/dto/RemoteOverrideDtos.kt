package com.niqob.app.data.remote.supabase.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateOverrideResponseDto(
    val success: Boolean,
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    @SerialName("claim_expires_at") val claimExpiresAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class PendingOverrideItemDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("requested_duration_minutes") val requestedDurationMinutes: Int = 15,
    @SerialName("approved_duration_minutes") val approvedDurationMinutes: Int? = null,
    val status: String,
    val reason: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("claim_expires_at") val claimExpiresAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("consumed_at") val consumedAt: String? = null,
    @SerialName("consumed_expires_at") val consumedExpiresAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null
)

@Serializable
data class GetPendingOverridesResponseDto(
    val success: Boolean,
    val requests: List<PendingOverrideItemDto> = emptyList(),
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class OverrideStatusResponseDto(
    val success: Boolean,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val status: String? = null,
    @SerialName("requested_duration_minutes") val requestedDurationMinutes: Int = 15,
    @SerialName("approved_duration_minutes") val approvedDurationMinutes: Int? = null,
    val reason: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("claim_expires_at") val claimExpiresAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("consumed_at") val consumedAt: String? = null,
    @SerialName("consumed_expires_at") val consumedExpiresAt: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_expired") val isExpired: Boolean = false,
    @SerialName("remaining_seconds") val remainingSeconds: Long = 0L,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class ApproveOverrideResponseDto(
    val success: Boolean,
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    @SerialName("approved_duration_minutes") val approvedDurationMinutes: Int? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("claim_expires_at") val claimExpiresAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class RejectOverrideResponseDto(
    val success: Boolean,
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class ConsumeOverrideResponseDto(
    val success: Boolean,
    val status: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("approved_duration_minutes") val approvedDurationMinutes: Int? = null,
    @SerialName("consumed_at") val consumedAt: String? = null,
    @SerialName("consumed_expires_at") val consumedExpiresAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class RevokeOverrideResponseDto(
    val success: Boolean,
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

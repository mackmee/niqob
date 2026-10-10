package com.niqob.app.data.remote.supabase.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileTable(
    val id: String,
    val role: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class FamilyTable(
    val id: String,
    @SerialName("parent_id") val parentId: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class DeviceTable(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "ANDROID",
    @SerialName("device_type") val deviceType: String = "CHILD_PHONE",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class DeviceInsertTable(
    @SerialName("family_id") val familyId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "ANDROID",
    @SerialName("device_type") val deviceType: String = "CHILD_PHONE"
)

@Serializable
data class PairingSessionTable(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("parent_device_id") val parentDeviceId: String,
    @SerialName("child_device_id") val childDeviceId: String? = null,
    @SerialName("pairing_code") val pairingCode: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null
)

@Serializable
data class CreatePairingRpcResponse(
    val success: Boolean,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("parent_device_id") val parentDeviceId: String? = null,
    @SerialName("pairing_code") val pairingCode: String? = null,
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val message: String? = null
)

@Serializable
data class PairChildRpcResponse(
    val success: Boolean,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    val status: String? = null,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
    @SerialName("device_secret") val deviceSecret: String? = null
)

@Serializable
data class HeartbeatRpcResponse(
    val success: Boolean,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

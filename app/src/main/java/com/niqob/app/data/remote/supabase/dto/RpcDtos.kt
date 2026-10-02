package com.niqob.app.data.remote.supabase.dto

data class PairChildResultDto(
    val success: Boolean,
    val sessionId: String? = null,
    val familyId: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val status: String? = null,
    val errorCode: String? = null,
    val message: String? = null
)

data class CreatePairingResultDto(
    val success: Boolean,
    val sessionId: String? = null,
    val familyId: String? = null,
    val parentDeviceId: String? = null,
    val pairingCode: String? = null,
    val status: String? = null,
    val expiresAt: Long? = null,
    val errorCode: String? = null,
    val message: String? = null
)

data class HeartbeatResultDto(
    val success: Boolean,
    val deviceId: String? = null,
    val lastSeenAt: Long? = null,
    val errorCode: String? = null,
    val message: String? = null
)

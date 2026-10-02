package com.niqob.app.data.remote.supabase.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScreenTimeLimitTable(
    val id: String? = null,
    @SerialName("family_id") val familyId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("daily_limit_minutes") val dailyLimitMinutes: Int = 120,
    val enabled: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SetScreenTimeLimitRpcResponse(
    val success: Boolean,
    val limit: ScreenTimeLimitTable? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class GetScreenTimeLimitRpcResponse(
    val success: Boolean,
    @SerialName("is_default") val isDefault: Boolean? = null,
    val limit: ScreenTimeLimitTable? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

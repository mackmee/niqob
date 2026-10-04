package com.niqob.app.data.remote.supabase.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DailyUsageRecordDto(
    val id: String? = null,
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("usage_date") val usageDate: String,
    @SerialName("total_usage_minutes") val totalUsageMinutes: Int = 0,
    @SerialName("daily_limit_minutes") val dailyLimitMinutes: Int = 120,
    @SerialName("remaining_minutes") val remainingMinutes: Int = 0,
    @SerialName("usage_percentage") val usagePercentage: Int = 0,
    @SerialName("is_limit_reached") val isLimitReached: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class UpsertDailyUsageResponseDto(
    val success: Boolean,
    val usage: DailyUsageRecordDto? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

@Serializable
data class GetDailyUsageResponseDto(
    val success: Boolean,
    @SerialName("has_data") val hasData: Boolean = false,
    val usage: DailyUsageRecordDto? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null
)

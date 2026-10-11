package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.data.remote.supabase.dto.DailyUsageRecordDto
import com.niqob.app.data.remote.supabase.dto.GetDailyUsageResponseDto
import com.niqob.app.data.remote.supabase.dto.UpsertDailyUsageResponseDto
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.domain.repository.ScreenTimeUsageRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SupabaseScreenTimeUsageRepository(
    private val clientProvider: () -> SupabaseClient = { NiqobSupabaseClient.getClient() },
    private val isConfiguredProvider: () -> Boolean = { NiqobSupabaseClient.isConfigured },
    private val credentialStore: ChildDeviceCredentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance()
) : ScreenTimeUsageRepository {

    override suspend fun upsertDailyUsage(
        deviceId: String,
        usageDate: String,
        totalUsageMinutes: Int,
        dailyLimitMinutes: Int?,
        remainingMinutes: Int?,
        usagePercentage: Int?,
        isLimitReached: Boolean?,
        deviceSecret: String?
    ): Result<ScreenTimeDailyUsage> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (usageDate.isBlank()) {
            return Result.Error(IllegalArgumentException("Usage date cannot be blank."))
        }
        if (totalUsageMinutes < 0) {
            return Result.Error(IllegalArgumentException("Total usage minutes cannot be negative."))
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
                function = "upsert_screen_time_usage_daily",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId)
                    put("p_usage_date", usageDate)
                    put("p_total_usage_minutes", totalUsageMinutes)
                    dailyLimitMinutes?.let { put("p_daily_limit_minutes", it) }
                    remainingMinutes?.let { put("p_remaining_minutes", it) }
                    usagePercentage?.let { put("p_usage_percentage", it) }
                    isLimitReached?.let { put("p_is_limit_reached", it) }
                    secret?.let { put("p_device_secret", it) }
                }
            ).decodeAs<UpsertDailyUsageResponseDto>()

            if (response.success && response.usage != null) {
                Result.Success(mapDtoToDomain(response.usage))
            } else {
                Result.Error(Exception(response.message ?: response.errorCode ?: "Failed to sync daily usage."))
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error syncing screen time usage: ${e.message}"))
        }
    }

    override suspend fun getDailyUsage(
        deviceId: String,
        usageDate: String,
        deviceSecret: String?
    ): Result<ScreenTimeDailyUsage?> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (usageDate.isBlank()) {
            return Result.Error(IllegalArgumentException("Usage date cannot be blank."))
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
                function = "get_screen_time_usage_daily",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId)
                    put("p_usage_date", usageDate)
                    secret?.let { put("p_device_secret", it) }
                }
            ).decodeAs<GetDailyUsageResponseDto>()

            if (response.success) {
                if (response.hasData && response.usage != null) {
                    Result.Success(mapDtoToDomain(response.usage))
                } else {
                    Result.Success(null)
                }
            } else {
                Result.Error(Exception(response.message ?: response.errorCode ?: "Failed to get daily usage."))
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error fetching screen time usage: ${e.message}"))
        }
    }

    fun mapDtoToDomain(dto: DailyUsageRecordDto): ScreenTimeDailyUsage {
        val now = System.currentTimeMillis()
        return ScreenTimeDailyUsage(
            id = dto.id,
            familyId = dto.familyId,
            deviceId = dto.deviceId,
            usageDate = dto.usageDate,
            totalUsageMinutes = dto.totalUsageMinutes,
            dailyLimitMinutes = dto.dailyLimitMinutes,
            remainingMinutes = dto.remainingMinutes,
            usagePercentage = dto.usagePercentage,
            isLimitReached = dto.isLimitReached,
            createdAt = parseIsoTimestamp(dto.createdAt) ?: now,
            updatedAt = parseIsoTimestamp(dto.updatedAt) ?: now
        )
    }

    private fun parseIsoTimestamp(isoString: String?): Long? {
        if (isoString.isNullOrBlank()) return null
        return try {
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            for (format in formats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = sdf.parse(isoString)
                    if (date != null) return date.time
                } catch (_: Exception) { }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

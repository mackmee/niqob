package com.niqob.app.data.repository

import com.niqob.app.core.common.ChildAuthorizationRevokedException
import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.data.remote.supabase.dto.GetScreenTimeLimitRpcResponse
import com.niqob.app.data.remote.supabase.dto.ScreenTimeLimitTable
import com.niqob.app.data.remote.supabase.dto.SetScreenTimeLimitRpcResponse
import com.niqob.app.domain.model.ScreenTimeLimit
import com.niqob.app.domain.repository.ScreenTimeRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SupabaseScreenTimeRepository(
    private val clientProvider: () -> SupabaseClient = { NiqobSupabaseClient.getClient() },
    private val isConfiguredProvider: () -> Boolean = { NiqobSupabaseClient.isConfigured },
    private val credentialStore: ChildDeviceCredentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance()
) : ScreenTimeRepository {

    override suspend fun getScreenTimeLimit(deviceId: String, deviceSecret: String?): Result<ScreenTimeLimit> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
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
                function = "get_screen_time_limit",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId)
                    secret?.let { put("p_device_secret", it) }
                }
            ).decodeAs<GetScreenTimeLimitRpcResponse>()

            if (response.success && response.limit != null) {
                Result.Success(mapTableToDomain(response.limit))
            } else {
                val errorCode = response.errorCode
                val msg = response.message ?: errorCode ?: "Failed to get screen time limit."
                if (errorCode == "UNAUTHORIZED" || msg.contains("UNAUTHORIZED") || msg.contains("Invalid device authorization")) {
                    Result.Error(ChildAuthorizationRevokedException(errorCode ?: "UNAUTHORIZED", msg))
                } else {
                    Result.Error(Exception(msg))
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("UNAUTHORIZED") || msg.contains("Invalid device authorization")) {
                Result.Error(ChildAuthorizationRevokedException("UNAUTHORIZED", msg))
            } else {
                Result.Error(Exception("Error getting screen time limit: ${e.message}"))
            }
        }
    }

    override suspend fun setScreenTimeLimit(
        deviceId: String,
        dailyLimitMinutes: Int,
        enabled: Boolean
    ): Result<ScreenTimeLimit> {
        if (dailyLimitMinutes < 0 || dailyLimitMinutes > 1440) {
            return Result.Error(IllegalArgumentException("Daily limit must be between 0 and 1440 minutes."))
        }

        if (!NiqobSupabaseClient.isConfigured) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            val response = client.postgrest.rpc(
                function = "set_screen_time_limit",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId)
                    put("p_daily_limit_minutes", dailyLimitMinutes)
                    put("p_enabled", enabled)
                }
            ).decodeAs<SetScreenTimeLimitRpcResponse>()

            if (response.success && response.limit != null) {
                Result.Success(mapTableToDomain(response.limit))
            } else {
                Result.Error(Exception(response.message ?: "Failed to set screen time limit."))
            }
        } catch (e: Exception) {
            Result.Error(Exception("Error setting screen time limit: ${e.message}"))
        }
    }

    fun mapTableToDomain(table: ScreenTimeLimitTable): ScreenTimeLimit {
        val now = System.currentTimeMillis()
        return ScreenTimeLimit(
            id = table.id,
            familyId = table.familyId,
            deviceId = table.deviceId,
            dailyLimitMinutes = table.dailyLimitMinutes,
            enabled = table.enabled,
            createdAt = parseIsoTimestamp(table.createdAt) ?: now,
            updatedAt = parseIsoTimestamp(table.updatedAt) ?: now
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

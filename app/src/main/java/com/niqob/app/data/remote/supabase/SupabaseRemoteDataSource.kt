package com.niqob.app.data.remote.supabase

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.dto.AuthResponseDto
import com.niqob.app.data.remote.supabase.dto.AuthSessionDto
import com.niqob.app.data.remote.supabase.dto.AuthUserDto
import com.niqob.app.data.remote.supabase.dto.CreatePairingResultDto
import com.niqob.app.data.remote.supabase.dto.DeviceDto
import com.niqob.app.data.remote.supabase.dto.PairChildResultDto
import com.niqob.app.data.remote.supabase.dto.PairingSessionDto
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SupabaseRemoteDataSource(
    private val httpClient: SupabaseHttpClient = DefaultSupabaseHttpClient()
) {

    suspend fun signUp(email: String, password: String): Result<AuthResponseDto> {
        val payload = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("data", JSONObject().apply {
                put("role", "PARENT")
            })
        }

        val response = httpClient.post("/auth/v1/signup", payload.toString())
        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Registration failed."))
        }

        return try {
            val json = JSONObject(response.body ?: "{}")
            val userJson = if (json.has("user")) json.getJSONObject("user") else json
            val userDto = parseUser(userJson)

            val sessionDto = if (json.has("access_token")) {
                AuthSessionDto(
                    accessToken = json.getString("access_token"),
                    tokenType = json.optString("token_type", "bearer"),
                    expiresIn = json.optLong("expires_in", 3600),
                    refreshToken = json.optNullableString("refresh_token"),
                    user = userDto
                )
            } else null


            Result.Success(AuthResponseDto(user = userDto, session = sessionDto))
        } catch (e: Exception) {
            Result.Error(Exception("Failed to parse registration response: ${e.message}"))
        }
    }

    suspend fun signIn(email: String, password: String): Result<AuthResponseDto> {
        val payload = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val response = httpClient.post("/auth/v1/token?grant_type=password", payload.toString())
        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Sign in failed."))
        }

        return try {
            val json = JSONObject(response.body ?: "{}")
            val accessToken = json.getString("access_token")
            val userJson = json.getJSONObject("user")
            val userDto = parseUser(userJson)

            val sessionDto = AuthSessionDto(
                accessToken = accessToken,
                tokenType = json.optString("token_type", "bearer"),
                expiresIn = json.optLong("expires_in", 3600),
                refreshToken = json.optNullableString("refresh_token"),
                user = userDto
            )


            Result.Success(AuthResponseDto(user = userDto, session = sessionDto))
        } catch (e: Exception) {
            Result.Error(Exception("Failed to parse sign in response: ${e.message}"))
        }
    }

    suspend fun signOut(authToken: String?): Result<Unit> {
        val response = httpClient.post("/auth/v1/logout", "{}", authToken = authToken)
        return if (response.isSuccessful || response.statusCode == 401) {
            Result.Success(Unit)
        } else {
            Result.Error(Exception(response.errorMessage ?: "Sign out failed."))
        }
    }

    suspend fun createPairingSession(parentDeviceId: String, authToken: String): Result<CreatePairingResultDto> {
        val payload = JSONObject().apply {
            put("p_parent_device_id", parentDeviceId)
        }

        val response = httpClient.post("/rest/v1/rpc/create_pairing_session", payload.toString(), authToken = authToken)
        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Failed to generate pairing session."))
        }

        return try {
            val json = JSONObject(response.body ?: "{}")
            val success = json.optBoolean("success", false)
            if (!success) {
                return Result.Error(Exception(json.optString("message", "Could not create pairing code.")))
            }

            val expiresAtMillis = parseIsoTimestamp(json.optString("expires_at")) ?: (System.currentTimeMillis() + 10 * 60 * 1000L)

            Result.Success(
                CreatePairingResultDto(
                    success = true,
                    sessionId = json.optString("session_id"),
                    familyId = json.optString("family_id"),
                    parentDeviceId = json.optString("parent_device_id"),
                    pairingCode = json.optString("pairing_code"),
                    status = json.optString("status", "PENDING"),
                    expiresAt = expiresAtMillis
                )
            )
        } catch (e: Exception) {
            Result.Error(Exception("Failed to parse pairing session response: ${e.message}"))
        }
    }

    suspend fun pairChildDevice(
        pairingCode: String,
        deviceName: String,
        platform: String = "ANDROID",
        deviceType: String = "CHILD_PHONE"
    ): Result<PairChildResultDto> {
        val payload = JSONObject().apply {
            put("p_pairing_code", pairingCode)
            put("p_device_name", deviceName)
            put("p_platform", platform)
            put("p_device_type", deviceType)
        }

        val response = httpClient.post("/rest/v1/rpc/pair_child_device", payload.toString())
        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Pairing request failed."))
        }

        return try {
            val json = JSONObject(response.body ?: "{}")
            val success = json.optBoolean("success", false)
            val errorCode = json.optNullableString("error_code")
            val message = json.optNullableString("message")

            if (!success) {
                return Result.Error(Exception(message ?: "Pairing code validation failed."))
            }

            Result.Success(
                PairChildResultDto(
                    success = true,
                    sessionId = json.optNullableString("session_id"),
                    familyId = json.optNullableString("family_id"),
                    deviceId = json.optNullableString("device_id"),
                    deviceName = json.optNullableString("device_name"),
                    status = json.optString("status", "CONNECTED"),
                    errorCode = errorCode,
                    message = message,
                    deviceSecret = json.optNullableString("device_secret")
                )
            )
        } catch (e: Exception) {

            Result.Error(Exception("Failed to parse child pairing response: ${e.message}"))
        }
    }

    suspend fun updateHeartbeat(deviceId: String, deviceSecret: String? = null): Result<Unit> {
        val payload = JSONObject().apply {
            put("p_device_id", deviceId)
            deviceSecret?.let { put("p_device_secret", it) }
        }

        val response = httpClient.post("/rest/v1/rpc/update_device_heartbeat", payload.toString())
        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Heartbeat failed."))
        }

        return try {
            val json = JSONObject(response.body ?: "{}")
            if (json.optBoolean("success", true)) {
                Result.Success(Unit)
            } else {
                Result.Error(Exception(json.optString("message", "Device not found.")))
            }
        } catch (e: Exception) {
            Result.Error(Exception("Heartbeat error: ${e.message}"))
        }
    }

    suspend fun revokePairingSession(sessionId: String, authToken: String): Result<Unit> {
        val payload = JSONObject().apply {
            put("p_session_id", sessionId)
        }

        val response = httpClient.post("/rest/v1/rpc/revoke_pairing_session", payload.toString(), authToken = authToken)
        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Revocation failed."))
        }

        return Result.Success(Unit)
    }

    suspend fun getDevices(familyId: String, authToken: String): Result<List<DeviceDto>> {
        val response = httpClient.get(
            "/rest/v1/devices?family_id=eq.$familyId&select=*&order=created_at.desc",
            authToken = authToken
        )

        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Failed to fetch devices."))
        }

        return try {
            val array = JSONArray(response.body ?: "[]")
            val list = mutableListOf<DeviceDto>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                list.add(
                    DeviceDto(
                        id = item.getString("id"),
                        familyId = item.getString("family_id"),
                        userId = item.optNullableString("user_id"),
                        deviceName = item.getString("device_name"),
                        platform = item.optString("platform", "ANDROID"),
                        deviceType = item.optString("device_type", "CHILD_PHONE"),
                        lastSeenAt = parseIsoTimestamp(item.optString("last_seen_at")) ?: System.currentTimeMillis(),
                        createdAt = parseIsoTimestamp(item.optString("created_at")) ?: System.currentTimeMillis(),
                        updatedAt = parseIsoTimestamp(item.optString("updated_at")) ?: System.currentTimeMillis()
                    )
                )
            }
            Result.Success(list)
        } catch (e: Exception) {
            Result.Error(Exception("Failed to parse devices list: ${e.message}"))
        }
    }

    suspend fun getPairingSession(sessionId: String, authToken: String): Result<PairingSessionDto?> {
        val response = httpClient.get(
            "/rest/v1/pairing_sessions?id=eq.$sessionId&select=*&limit=1",
            authToken = authToken
        )

        if (!response.isSuccessful) {
            return Result.Error(Exception(response.errorMessage ?: "Failed to fetch pairing session."))
        }

        return try {
            val array = JSONArray(response.body ?: "[]")
            if (array.length() == 0) {
                return Result.Success(null)
            }
            val item = array.getJSONObject(0)
            val session = PairingSessionDto(
                id = item.getString("id"),
                familyId = item.getString("family_id"),
                parentDeviceId = item.getString("parent_device_id"),
                childDeviceId = item.optNullableString("child_device_id"),
                pairingCode = item.getString("pairing_code"),
                status = item.getString("status"),
                expiresAt = parseIsoTimestamp(item.optString("expires_at")) ?: (System.currentTimeMillis() + 10 * 60 * 1000L),
                createdAt = parseIsoTimestamp(item.optString("created_at")) ?: System.currentTimeMillis(),
                connectedAt = parseIsoTimestamp(item.optNullableString("connected_at")),
                revokedAt = parseIsoTimestamp(item.optNullableString("revoked_at"))
            )
            Result.Success(session)
        } catch (e: Exception) {
            Result.Error(Exception("Failed to parse pairing session: ${e.message}"))
        }
    }

    private fun parseUser(json: JSONObject): AuthUserDto {
        val id = json.getString("id")
        val email = json.optString("email", "")
        var role = "PARENT"
        var displayName: String? = null

        if (json.has("user_metadata")) {
            val meta = json.getJSONObject("user_metadata")
            role = meta.optString("role", "PARENT")
            displayName = meta.optNullableString("display_name")
        }

        val createdAtMillis = parseIsoTimestamp(json.optString("created_at")) ?: System.currentTimeMillis()

        return AuthUserDto(
            id = id,
            email = email,
            role = role,
            displayName = displayName,
            createdAt = createdAtMillis
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        return if (has(key) && !isNull(key)) {
            val str = optString(key)
            if (str.isEmpty() || str == "null") null else str
        } else null
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


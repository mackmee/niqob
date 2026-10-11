package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.data.remote.supabase.dto.DeviceInsertTable
import com.niqob.app.data.remote.supabase.dto.DeviceTable
import com.niqob.app.data.remote.supabase.dto.HeartbeatRpcResponse
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.repository.DeviceRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SupabaseDeviceRepository(
    private val clientProvider: () -> SupabaseClient = { NiqobSupabaseClient.getClient() },
    private val isConfiguredProvider: () -> Boolean = { NiqobSupabaseClient.isConfigured },
    private val credentialStore: ChildDeviceCredentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance()
) : DeviceRepository {

    private val _devicesFlow = MutableStateFlow<List<Device>>(emptyList())

    override suspend fun registerDevice(
        familyId: String,
        deviceName: String,
        platform: String,
        deviceType: String
    ): Result<Device> {
        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = clientProvider()
            val insertPayload = DeviceInsertTable(
                familyId = familyId,
                deviceName = deviceName.ifBlank { "Child Device" },
                platform = platform,
                deviceType = deviceType
            )

            val insertedDevice = client.postgrest["devices"]
                .insert(insertPayload) { select() }
                .decodeSingle<DeviceTable>()

            val device = Device(
                id = insertedDevice.id,
                familyId = insertedDevice.familyId,
                userId = insertedDevice.userId,
                deviceName = insertedDevice.deviceName,
                platform = insertedDevice.platform,
                deviceType = insertedDevice.deviceType,
                lastSeenAt = parseIsoTimestamp(insertedDevice.lastSeenAt) ?: System.currentTimeMillis()
            )

            _devicesFlow.value = _devicesFlow.value + device
            Result.Success(device)
        } catch (e: Exception) {
            Result.Error(Exception("Device registration failed: ${e.message}"))
        }
    }

    override suspend fun sendHeartbeat(deviceId: String, deviceSecret: String?): Result<Unit> {
        if (!isConfiguredProvider()) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        val secret = (deviceSecret ?: credentialStore.getDeviceSecret())?.trim()

        return try {
            val client = clientProvider()
            val response = client.postgrest.rpc(
                function = "update_device_heartbeat",
                parameters = buildJsonObject {
                    put("p_device_id", deviceId)
                    secret?.let { put("p_device_secret", it) }
                }
            ).decodeAs<HeartbeatRpcResponse>()

            if (response.success) {
                Result.Success(Unit)
            } else {
                Result.Error(Exception(response.message ?: response.errorCode ?: "Heartbeat failed on the server."))
            }
        } catch (e: Exception) {
            Result.Error(Exception("Heartbeat error: ${e.message}"))
        }
    }

    override suspend fun fetchDevices(): Result<List<Device>> {
        if (!NiqobSupabaseClient.isConfigured) {
            return Result.Success(_devicesFlow.value)
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            val deviceTables = client.postgrest["devices"]
                .select()
                .decodeList<DeviceTable>()
                .sortedByDescending { it.lastSeenAt }

            val devices = deviceTables.map { table ->
                Device(
                    id = table.id,
                    familyId = table.familyId,
                    userId = table.userId,
                    deviceName = table.deviceName,
                    platform = table.platform,
                    deviceType = table.deviceType,
                    lastSeenAt = parseIsoTimestamp(table.lastSeenAt) ?: System.currentTimeMillis()
                )
            }
            _devicesFlow.value = devices
            Result.Success(devices)
        } catch (e: Exception) {
            Result.Error(Exception("Failed to fetch devices: ${e.message}"))
        }
    }

    override fun getDevicesForFamily(familyId: String): Flow<List<Device>> {
        return _devicesFlow.asStateFlow()
    }

    override fun calculateDeviceConnectivity(lastSeenAtMillis: Long): DeviceConnectivityStatus {
        return DeviceConnectivityStatus.calculate(lastSeenAtMillis)
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

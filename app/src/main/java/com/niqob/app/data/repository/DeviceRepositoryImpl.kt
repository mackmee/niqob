package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.SupabaseConfig
import com.niqob.app.data.remote.supabase.SupabaseRemoteDataSource
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DeviceRepositoryImpl(
    private val remoteDataSource: SupabaseRemoteDataSource = SupabaseRemoteDataSource(),
    private val authRepository: AuthRepositoryImpl = AuthRepositoryImpl.getInstance(),
    private val credentialStore: ChildDeviceCredentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance()
) : DeviceRepository {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())

    override suspend fun registerDevice(
        familyId: String,
        deviceName: String,
        platform: String,
        deviceType: String
    ): Result<Device> {
        val newDevice = Device(
            id = UUID.randomUUID().toString(),
            familyId = familyId,
            userId = null,
            deviceName = deviceName,
            platform = platform,
            deviceType = deviceType,
            lastSeenAt = System.currentTimeMillis()
        )

        val updatedList = _devices.value.toMutableList().apply { add(newDevice) }
        _devices.value = updatedList
        return Result.Success(newDevice)
    }

    override suspend fun sendHeartbeat(deviceId: String, deviceSecret: String?): Result<Unit> {
        val secret = (deviceSecret ?: credentialStore.getDeviceSecret())?.trim()
        if (SupabaseConfig.isConfigured) {
            val result = remoteDataSource.updateHeartbeat(deviceId, secret)
            if (result is Result.Success) {
                updateLocalDeviceTimestamp(deviceId, System.currentTimeMillis())
            }
            return result
        } else {
            // Local fallback
            updateLocalDeviceTimestamp(deviceId, System.currentTimeMillis())
            return Result.Success(Unit)
        }
    }

    override fun getDevicesForFamily(familyId: String): Flow<List<Device>> {
        return _devices.asStateFlow()
    }

    override fun calculateDeviceConnectivity(lastSeenAtMillis: Long): DeviceConnectivityStatus {
        return DeviceConnectivityStatus.calculate(lastSeenAtMillis)
    }

    fun setDevicesForTesting(devices: List<Device>) {
        _devices.value = devices
    }

    private fun updateLocalDeviceTimestamp(deviceId: String, timestamp: Long) {
        val currentList = _devices.value
        val index = currentList.indexOfFirst { it.id == deviceId }
        if (index != -1) {
            val updated = currentList[index].copy(lastSeenAt = timestamp, updatedAt = timestamp)
            val newList = currentList.toMutableList().apply { set(index, updated) }
            _devices.value = newList
        }
    }
}

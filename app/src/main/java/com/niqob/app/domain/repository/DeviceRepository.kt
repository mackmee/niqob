package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    suspend fun registerDevice(
        familyId: String,
        deviceName: String,
        platform: String = "ANDROID",
        deviceType: String = "CHILD_PHONE"
    ): Result<Device>

    suspend fun sendHeartbeat(deviceId: String): Result<Unit>
    fun getDevicesForFamily(familyId: String): Flow<List<Device>>
    fun calculateDeviceConnectivity(lastSeenAtMillis: Long): DeviceConnectivityStatus
    suspend fun fetchDevices(): Result<List<Device>> = Result.Success(emptyList())
}

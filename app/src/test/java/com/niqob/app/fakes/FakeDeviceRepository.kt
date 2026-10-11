package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

class FakeDeviceRepository : DeviceRepository {

    private val _devicesFlow = MutableStateFlow<List<Device>>(emptyList())
    var currentMockTimeMillis: Long = System.currentTimeMillis()
    var shouldFailRegistration: Boolean = false
    var shouldFailHeartbeat: Boolean = false

    override suspend fun registerDevice(
        familyId: String,
        deviceName: String,
        platform: String,
        deviceType: String
    ): Result<Device> {
        if (shouldFailRegistration) {
            return Result.Error(Exception("Device registration failed (simulated)."))
        }

        val device = Device(
            id = UUID.randomUUID().toString(),
            familyId = familyId,
            userId = null,
            deviceName = deviceName.ifBlank { "Child Device" },
            platform = platform,
            deviceType = deviceType,
            lastSeenAt = currentMockTimeMillis
        )
        _devicesFlow.value = _devicesFlow.value + device
        return Result.Success(device)
    }

    override suspend fun sendHeartbeat(deviceId: String, deviceSecret: String?): Result<Unit> {
        if (shouldFailHeartbeat) {
            return Result.Error(Exception("Heartbeat failed (simulated)."))
        }

        val currentList = _devicesFlow.value
        val index = currentList.indexOfFirst { it.id == deviceId }
        if (index != -1) {
            val updated = currentList[index].copy(lastSeenAt = currentMockTimeMillis)
            _devicesFlow.value = currentList.toMutableList().also { it[index] = updated }
            return Result.Success(Unit)
        }
        return Result.Error(Exception("Device not found: $deviceId"))
    }

    override fun getDevicesForFamily(familyId: String): Flow<List<Device>> {
        return _devicesFlow.map { list -> list.filter { it.familyId == familyId } }
    }

    override fun calculateDeviceConnectivity(lastSeenAtMillis: Long): DeviceConnectivityStatus {
        return DeviceConnectivityStatus.calculate(lastSeenAtMillis, currentMockTimeMillis)
    }

    fun setDevicesDirectly(devices: List<Device>) {
        _devicesFlow.value = devices
    }
}

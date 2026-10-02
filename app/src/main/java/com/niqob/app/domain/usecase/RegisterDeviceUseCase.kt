package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.repository.DeviceRepository

class RegisterDeviceUseCase(
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(
        familyId: String,
        deviceName: String,
        platform: String = "ANDROID",
        deviceType: String = "CHILD_PHONE"
    ): Result<Device> {
        val trimmedName = deviceName.trim()
        val validName = if (trimmedName.isEmpty()) "Child Device" else trimmedName
        return deviceRepository.registerDevice(familyId, validName, platform, deviceType)
    }
}

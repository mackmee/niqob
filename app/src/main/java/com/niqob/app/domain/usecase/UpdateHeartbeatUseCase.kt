package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.repository.DeviceRepository

class UpdateHeartbeatUseCase(
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(deviceId: String): Result<Unit> {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be empty."))
        }
        return deviceRepository.sendHeartbeat(trimmedId)
    }
}

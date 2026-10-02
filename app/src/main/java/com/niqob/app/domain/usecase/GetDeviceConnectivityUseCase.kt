package com.niqob.app.domain.usecase

import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.repository.DeviceRepository

class GetDeviceConnectivityUseCase(
    private val deviceRepository: DeviceRepository
) {
    operator fun invoke(lastSeenAtMillis: Long): DeviceConnectivityStatus {
        return deviceRepository.calculateDeviceConnectivity(lastSeenAtMillis)
    }
}

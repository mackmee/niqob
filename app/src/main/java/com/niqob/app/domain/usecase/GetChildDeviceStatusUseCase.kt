package com.niqob.app.domain.usecase

import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.repository.NiqobRepository
import kotlinx.coroutines.flow.Flow

class GetChildDeviceStatusUseCase(
    private val repository: NiqobRepository
) {
    operator fun invoke(): Flow<ChildDeviceStatus> {
        return repository.getChildDeviceStatus()
    }
}

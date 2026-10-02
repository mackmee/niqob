package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.repository.PairingRepository

class ConnectChildDeviceUseCase(
    private val repository: PairingRepository
) {
    suspend operator fun invoke(pairingCode: String): Result<PairingSession> {
        return repository.connectChild(pairingCode)
    }
}

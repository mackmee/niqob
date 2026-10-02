package com.niqob.app.domain.usecase

import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow

class GetPairingSessionUseCase(
    private val repository: PairingRepository
) {
    operator fun invoke(): Flow<PairingSession?> {
        return repository.getPairingSession()
    }
}

package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.repository.PairingRepository

class CreatePairingCodeUseCase(
    private val repository: PairingRepository
) {
    suspend operator fun invoke(): Result<PairingSession> {
        return repository.createPairingCode()
    }
}

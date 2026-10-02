package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.repository.PairingRepository

class RevokePairingUseCase(
    private val repository: PairingRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return repository.revokePairing()
    }
}

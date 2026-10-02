package com.niqob.app.domain.usecase

import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository

class SetUserRoleUseCase(
    private val repository: PairingRepository
) {
    suspend operator fun invoke(role: UserMode) {
        repository.setCurrentRole(role)
    }
}

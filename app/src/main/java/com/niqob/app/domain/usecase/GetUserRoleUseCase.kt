package com.niqob.app.domain.usecase

import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow

class GetUserRoleUseCase(
    private val repository: PairingRepository
) {
    operator fun invoke(): Flow<UserMode> {
        return repository.getCurrentRole()
    }
}

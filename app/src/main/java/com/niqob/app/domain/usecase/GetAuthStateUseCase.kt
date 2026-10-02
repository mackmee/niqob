package com.niqob.app.domain.usecase

import com.niqob.app.domain.repository.AuthRepository
import com.niqob.app.domain.repository.AuthState
import kotlinx.coroutines.flow.StateFlow

class GetAuthStateUseCase(
    private val authRepository: AuthRepository
) {
    operator fun invoke(): StateFlow<AuthState> {
        return authRepository.authState
    }
}

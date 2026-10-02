package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.User
import com.niqob.app.domain.repository.AuthRepository

class ParentLoginUseCase(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(email: String, password: String): Result<User> {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || !trimmedEmail.contains("@")) {
            return Result.Error(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.isEmpty()) {
            return Result.Error(IllegalArgumentException("Password cannot be empty."))
        }
        return authRepository.signInParent(trimmedEmail, password)
    }
}

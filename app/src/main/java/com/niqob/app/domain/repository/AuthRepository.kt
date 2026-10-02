package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.User
import kotlinx.coroutines.flow.StateFlow

sealed class AuthState {
    data object Initial : AuthState()
    data object Loading : AuthState()
    data class Authenticated(val user: User) : AuthState()
    data object Unauthenticated : AuthState()
    data class Error(val message: String) : AuthState()
}

interface AuthRepository {
    val authState: StateFlow<AuthState>

    suspend fun signUpParent(email: String, password: String): Result<User>
    suspend fun signInParent(email: String, password: String): Result<User>
    suspend fun signOut(): Result<Unit>
    suspend fun restoreSession(): Result<User?>
    fun getCurrentUser(): User?
}

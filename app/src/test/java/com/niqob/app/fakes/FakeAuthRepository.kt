package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserRole
import com.niqob.app.domain.repository.AuthRepository
import com.niqob.app.domain.repository.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class FakeAuthRepository : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var currentUser: User? = null
    var shouldFailWithNetworkError: Boolean = false
    var shouldFailWithInvalidCredentials: Boolean = false

    override suspend fun signUpParent(email: String, password: String): Result<User> {
        if (shouldFailWithNetworkError) {
            val err = "Connection unavailable. Please check your internet connection."
            _authState.value = AuthState.Error(err)
            return Result.Error(Exception(err))
        }

        _authState.value = AuthState.Loading
        val user = User(
            id = UUID.randomUUID().toString(),
            email = email,
            role = UserRole.PARENT,
            displayName = email.substringBefore("@")
        )
        currentUser = user
        _authState.value = AuthState.Authenticated(user)
        return Result.Success(user)
    }

    override suspend fun signInParent(email: String, password: String): Result<User> {
        if (shouldFailWithNetworkError) {
            val err = "Connection unavailable. Please check your internet connection."
            _authState.value = AuthState.Error(err)
            return Result.Error(Exception(err))
        }
        if (shouldFailWithInvalidCredentials) {
            val err = "Invalid email or password."
            _authState.value = AuthState.Error(err)
            return Result.Error(Exception(err))
        }

        _authState.value = AuthState.Loading
        val user = User(
            id = UUID.randomUUID().toString(),
            email = email,
            role = UserRole.PARENT,
            displayName = email.substringBefore("@")
        )
        currentUser = user
        _authState.value = AuthState.Authenticated(user)
        return Result.Success(user)
    }

    override suspend fun signOut(): Result<Unit> {
        currentUser = null
        _authState.value = AuthState.Unauthenticated
        return Result.Success(Unit)
    }

    override suspend fun restoreSession(): Result<User?> {
        val user = currentUser
        return if (user != null) {
            _authState.value = AuthState.Authenticated(user)
            Result.Success(user)
        } else {
            _authState.value = AuthState.Unauthenticated
            Result.Success(null)
        }
    }

    override fun getCurrentUser(): User? = currentUser

    fun setAuthenticatedUser(user: User) {
        currentUser = user
        _authState.value = AuthState.Authenticated(user)
    }
}

package com.niqob.app.data.repository

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserRole
import com.niqob.app.domain.repository.AuthRepository
import com.niqob.app.domain.repository.AuthState
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseAuthRepository : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkInitialSession()
    }

    private fun checkInitialSession() {
        if (!NiqobSupabaseClient.isConfigured) {
            _authState.value = AuthState.Unauthenticated
            return
        }

        try {
            val client = NiqobSupabaseClient.getClient()
            val session = client.auth.currentSessionOrNull()
            if (session != null) {
                val authUser = session.user
                if (authUser != null) {
                    val user = User(
                        id = authUser.id,
                        email = authUser.email ?: "",
                        role = UserRole.PARENT,
                        displayName = authUser.userMetadata?.get("display_name")?.toString()?.trim('"')
                    )
                    _authState.value = AuthState.Authenticated(user)
                    return
                }
            }
            _authState.value = AuthState.Unauthenticated
        } catch (_: Exception) {
            _authState.value = AuthState.Unauthenticated
        }
    }

    override suspend fun signUpParent(email: String, password: String): Result<User> {
        if (!NiqobSupabaseClient.isConfigured) {
            val errorMsg = "Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties."
            _authState.value = AuthState.Error(errorMsg)
            return Result.Error(IllegalStateException(errorMsg))
        }

        _authState.value = AuthState.Loading
        return try {
            val client = NiqobSupabaseClient.getClient()
            client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
                data = buildJsonObject {
                    put("role", "PARENT")
                }
            }

            val session = client.auth.currentSessionOrNull()
            val authUser = session?.user ?: client.auth.currentUserOrNull()

            if (authUser != null) {
                val user = User(
                    id = authUser.id,
                    email = authUser.email ?: email.trim(),
                    role = UserRole.PARENT,
                    displayName = authUser.userMetadata?.get("display_name")?.toString()?.trim('"') ?: email.substringBefore("@")
                )
                _authState.value = AuthState.Authenticated(user)
                Result.Success(user)
            } else {
                // If email confirmation is enabled on Supabase, the user is registered but requires confirmation
                val user = User(
                    id = "pending_confirmation",
                    email = email.trim(),
                    role = UserRole.PARENT,
                    displayName = email.substringBefore("@")
                )
                _authState.value = AuthState.Authenticated(user)
                Result.Success(user)
            }
        } catch (e: Exception) {
            val friendlyError = mapAuthError(e)
            _authState.value = AuthState.Error(friendlyError)
            Result.Error(Exception(friendlyError))
        }
    }

    override suspend fun signInParent(email: String, password: String): Result<User> {
        if (!NiqobSupabaseClient.isConfigured) {
            val errorMsg = "Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties."
            _authState.value = AuthState.Error(errorMsg)
            return Result.Error(IllegalStateException(errorMsg))
        }

        _authState.value = AuthState.Loading
        return try {
            val client = NiqobSupabaseClient.getClient()
            client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }

            val authUser = client.auth.currentUserOrNull()
            if (authUser != null) {
                val user = User(
                    id = authUser.id,
                    email = authUser.email ?: email.trim(),
                    role = UserRole.PARENT,
                    displayName = authUser.userMetadata?.get("display_name")?.toString()?.trim('"') ?: email.substringBefore("@")
                )
                _authState.value = AuthState.Authenticated(user)
                Result.Success(user)
            } else {
                val errorMsg = "Authentication failed: No user returned."
                _authState.value = AuthState.Error(errorMsg)
                Result.Error(Exception(errorMsg))
            }
        } catch (e: Exception) {
            val friendlyError = mapAuthError(e)
            _authState.value = AuthState.Error(friendlyError)
            Result.Error(Exception(friendlyError))
        }
    }

    override suspend fun signOut(): Result<Unit> {
        if (!NiqobSupabaseClient.isConfigured) {
            _authState.value = AuthState.Unauthenticated
            return Result.Success(Unit)
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            client.auth.signOut()
            _authState.value = AuthState.Unauthenticated
            Result.Success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Unauthenticated
            Result.Success(Unit)
        }
    }

    override suspend fun restoreSession(): Result<User?> {
        if (!NiqobSupabaseClient.isConfigured) {
            _authState.value = AuthState.Unauthenticated
            return Result.Success(null)
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            val session = client.auth.currentSessionOrNull()
            if (session != null) {
                val authUser = session.user
                if (authUser != null) {
                    val user = User(
                        id = authUser.id,
                        email = authUser.email ?: "",
                        role = UserRole.PARENT,
                        displayName = authUser.userMetadata?.get("display_name")?.toString()?.trim('"')
                    )
                    _authState.value = AuthState.Authenticated(user)
                    return Result.Success(user)
                }
            }
            _authState.value = AuthState.Unauthenticated
            Result.Success(null)
        } catch (e: Exception) {
            _authState.value = AuthState.Unauthenticated
            Result.Error(e)
        }
    }

    override fun getCurrentUser(): User? {
        val current = _authState.value
        if (current is AuthState.Authenticated) {
            return current.user
        }
        if (!NiqobSupabaseClient.isConfigured) return null
        return try {
            val authUser = NiqobSupabaseClient.getClient().auth.currentUserOrNull() ?: return null
            User(
                id = authUser.id,
                email = authUser.email ?: "",
                role = UserRole.PARENT,
                displayName = authUser.userMetadata?.get("display_name")?.toString()?.trim('"')
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mapAuthError(e: Throwable): String {
        val message = e.message?.lowercase() ?: ""
        return when {
            message.contains("invalid login credentials") || message.contains("invalid_credentials") ->
                "Invalid email or password."
            message.contains("user already registered") || message.contains("already exists") ->
                "An account with this email already exists."
            message.contains("password should be at least") ->
                "Password must be at least 6 characters long."
            message.contains("rate limit") ->
                "Too many attempts. Please wait a moment before trying again."
            message.contains("network") || message.contains("unable to resolve host") || message.contains("connect") ->
                "Connection unavailable. Please check your internet connection."
            else ->
                e.message ?: "Authentication failed. Please verify your credentials."
        }
    }
}

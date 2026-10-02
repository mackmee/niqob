package com.niqob.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.SupabaseConfig
import com.niqob.app.data.remote.supabase.SupabaseRemoteDataSource
import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserRole
import com.niqob.app.domain.repository.AuthRepository
import com.niqob.app.domain.repository.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AuthRepositoryImpl(
    private val remoteDataSource: SupabaseRemoteDataSource = SupabaseRemoteDataSource(),
    private val sharedPreferences: SharedPreferences? = null
) : AuthRepository {

    companion object {
        private const val PREF_AUTH_TOKEN = "niqob_auth_token"
        private const val PREF_USER_ID = "niqob_user_id"
        private const val PREF_USER_EMAIL = "niqob_user_email"
        private const val PREF_USER_ROLE = "niqob_user_role"
        private const val PREF_USER_NAME = "niqob_user_name"
        private const val PREF_USER_CREATED_AT = "niqob_user_created_at"

        @Volatile
        private var instance: AuthRepositoryImpl? = null

        fun getInstance(context: Context? = null): AuthRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: AuthRepositoryImpl(
                    sharedPreferences = context?.applicationContext?.getSharedPreferences("niqob_auth_prefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }

        fun resetInstanceForTests() {
            instance = null
        }
    }

    private val _authState = MutableStateFlow<AuthState>(restoreSessionInternal())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var currentAuthToken: String? = sharedPreferences?.getString(PREF_AUTH_TOKEN, null)

    fun getAuthToken(): String? = currentAuthToken

    override suspend fun signUpParent(email: String, password: String): Result<User> {
        _authState.value = AuthState.Loading

        if (SupabaseConfig.isConfigured) {
            return when (val remoteResult = remoteDataSource.signUp(email, password)) {
                is Result.Success -> {
                    val authResponse = remoteResult.data
                    val user = authResponse.user.toDomain()
                    val token = authResponse.session?.accessToken

                    currentAuthToken = token
                    persistUser(user, token)
                    _authState.value = AuthState.Authenticated(user)
                    Result.Success(user)
                }
                is Result.Error -> {
                    _authState.value = AuthState.Error(remoteResult.message ?: "Registration failed.")
                    Result.Error(remoteResult.exception)
                }
                is Result.Loading -> Result.Loading
            }
        } else {
            // Local fallback when Supabase is not configured (e.g. offline testing)
            val fallbackUser = User(
                id = UUID.randomUUID().toString(),
                email = email,
                role = UserRole.PARENT,
                displayName = email.substringBefore("@"),
                createdAt = System.currentTimeMillis()
            )
            val dummyToken = "local_mock_token_${UUID.randomUUID()}"
            currentAuthToken = dummyToken
            persistUser(fallbackUser, dummyToken)
            _authState.value = AuthState.Authenticated(fallbackUser)
            return Result.Success(fallbackUser)
        }
    }

    override suspend fun signInParent(email: String, password: String): Result<User> {
        _authState.value = AuthState.Loading

        if (SupabaseConfig.isConfigured) {
            return when (val remoteResult = remoteDataSource.signIn(email, password)) {
                is Result.Success -> {
                    val authResponse = remoteResult.data
                    val user = authResponse.user.toDomain()
                    val token = authResponse.session?.accessToken

                    currentAuthToken = token
                    persistUser(user, token)
                    _authState.value = AuthState.Authenticated(user)
                    Result.Success(user)
                }
                is Result.Error -> {
                    _authState.value = AuthState.Error(remoteResult.message ?: "Sign in failed.")
                    Result.Error(remoteResult.exception)
                }
                is Result.Loading -> Result.Loading
            }
        } else {

            // Local fallback when Supabase is not configured
            val savedEmail = sharedPreferences?.getString(PREF_USER_EMAIL, null)
            val userId = sharedPreferences?.getString(PREF_USER_ID, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
            val user = User(
                id = userId,
                email = if (!savedEmail.isNullOrBlank()) savedEmail else email,
                role = UserRole.PARENT,
                displayName = email.substringBefore("@"),
                createdAt = System.currentTimeMillis()
            )
            val dummyToken = "local_mock_token_${UUID.randomUUID()}"
            currentAuthToken = dummyToken
            persistUser(user, dummyToken)
            _authState.value = AuthState.Authenticated(user)
            return Result.Success(user)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        if (SupabaseConfig.isConfigured && currentAuthToken != null) {
            remoteDataSource.signOut(currentAuthToken)
        }

        currentAuthToken = null
        clearUser()
        _authState.value = AuthState.Unauthenticated
        return Result.Success(Unit)
    }

    override suspend fun restoreSession(): Result<User?> {
        val user = getCurrentUser()
        return if (user != null) {
            _authState.value = AuthState.Authenticated(user)
            Result.Success(user)
        } else {
            _authState.value = AuthState.Unauthenticated
            Result.Success(null)
        }
    }

    override fun getCurrentUser(): User? {
        val current = _authState.value
        if (current is AuthState.Authenticated) {
            return current.user
        }

        val id = sharedPreferences?.getString(PREF_USER_ID, null) ?: return null
        val email = sharedPreferences.getString(PREF_USER_EMAIL, null) ?: return null
        val roleStr = sharedPreferences.getString(PREF_USER_ROLE, UserRole.PARENT.name) ?: UserRole.PARENT.name
        val name = sharedPreferences.getString(PREF_USER_NAME, null)
        val createdAt = sharedPreferences.getLong(PREF_USER_CREATED_AT, System.currentTimeMillis())

        val role = try {
            UserRole.valueOf(roleStr)
        } catch (_: Exception) {
            UserRole.PARENT
        }

        return User(
            id = id,
            email = email,
            role = role,
            displayName = name,
            createdAt = createdAt
        )
    }

    private fun persistUser(user: User, token: String?) {
        sharedPreferences?.edit()?.apply {
            putString(PREF_USER_ID, user.id)
            putString(PREF_USER_EMAIL, user.email)
            putString(PREF_USER_ROLE, user.role.name)
            putString(PREF_USER_NAME, user.displayName)
            putLong(PREF_USER_CREATED_AT, user.createdAt)
            putString(PREF_AUTH_TOKEN, token)
            apply()
        }
    }

    private fun clearUser() {
        sharedPreferences?.edit()?.apply {
            remove(PREF_USER_ID)
            remove(PREF_USER_EMAIL)
            remove(PREF_USER_ROLE)
            remove(PREF_USER_NAME)
            remove(PREF_USER_CREATED_AT)
            remove(PREF_AUTH_TOKEN)
            apply()
        }
    }

    private fun restoreSessionInternal(): AuthState {
        val id = sharedPreferences?.getString(PREF_USER_ID, null)
        val email = sharedPreferences?.getString(PREF_USER_EMAIL, null)
        if (id.isNullOrBlank() || email.isNullOrBlank()) {
            return AuthState.Unauthenticated
        }
        val roleStr = sharedPreferences.getString(PREF_USER_ROLE, UserRole.PARENT.name) ?: UserRole.PARENT.name
        val name = sharedPreferences.getString(PREF_USER_NAME, null)
        val createdAt = sharedPreferences.getLong(PREF_USER_CREATED_AT, System.currentTimeMillis())

        val role = try {
            UserRole.valueOf(roleStr)
        } catch (_: Exception) {
            UserRole.PARENT
        }

        return AuthState.Authenticated(
            User(id = id, email = email, role = role, displayName = name, createdAt = createdAt)
        )
    }
}

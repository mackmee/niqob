package com.niqob.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.SupabaseAuthRepository
import com.niqob.app.domain.usecase.ParentLoginUseCase
import com.niqob.app.domain.usecase.ParentRegisterUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class AuthViewModel(
    private val loginUseCase: ParentLoginUseCase = ParentLoginUseCase(SupabaseAuthRepository()),
    private val registerUseCase: ParentRegisterUseCase = ParentRegisterUseCase(SupabaseAuthRepository())
) : ViewModel() {


    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(email = email, errorMessage = null)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun onConfirmPasswordChanged(confirm: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = confirm, errorMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value
        val email = state.email.trim()
        val password = state.password

        if (email.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "Please enter your email.")
            return
        }
        if (password.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "Please enter your password.")
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)

        try {
            viewModelScope.launch {
                when (val result = loginUseCase(email, password)) {
                    is Result.Success -> {
                        _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                        try {
                            com.niqob.app.data.repository.SupabasePairingRepository.getInstance().setCurrentRole(com.niqob.app.domain.model.UserMode.PARENT)
                        } catch (_: Throwable) { }
                        onSuccess()
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = result.message ?: "Login failed. Please check your credentials."
                        )
                    }
                    is Result.Loading -> {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                    }
                }
            }
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun register(onSuccess: () -> Unit) {
        val state = _uiState.value
        val email = state.email.trim()
        val password = state.password
        val confirm = state.confirmPassword

        if (email.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "Please enter your email.")
            return
        }
        if (password.length < 6) {
            _uiState.value = state.copy(errorMessage = "Password must be at least 6 characters.")
            return
        }
        if (password != confirm) {
            _uiState.value = state.copy(errorMessage = "Passwords do not match.")
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)

        try {
            viewModelScope.launch {
                when (val result = registerUseCase(email, password)) {
                    is Result.Success -> {
                        _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                        onSuccess()
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = result.message ?: "Registration failed. Please try again."
                        )
                    }
                    is Result.Loading -> {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                    }
                }
            }
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

}

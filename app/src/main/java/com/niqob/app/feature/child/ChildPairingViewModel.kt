package com.niqob.app.feature.child

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.usecase.ConnectChildDeviceUseCase
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChildPairingUiState(
    val codeInput: String = "",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ChildPairingViewModel(
    private val connectChildDeviceUseCase: ConnectChildDeviceUseCase = ConnectChildDeviceUseCase(SupabasePairingRepository.getInstance()),
    private val getPairingSessionUseCase: GetPairingSessionUseCase = GetPairingSessionUseCase(SupabasePairingRepository.getInstance())
) : ViewModel() {



    private val _uiState = MutableStateFlow(ChildPairingUiState())
    val uiState: StateFlow<ChildPairingUiState> = _uiState.asStateFlow()

    fun onCodeChanged(newCode: String) {
        val filtered = newCode.filter { it.isDigit() }.take(6)
        _uiState.value = _uiState.value.copy(
            codeInput = filtered,
            errorMessage = null
        )
    }

    fun connectToParent(onSuccess: () -> Unit = {}) {
        val code = _uiState.value.codeInput
        if (code.length != 6) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Please enter a 6-digit pairing code."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = connectChildDeviceUseCase(code)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        successMessage = "Parent connected successfully.",
                        errorMessage = null
                    )
                    onSuccess()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = false,
                        errorMessage = result.message ?: "Invalid pairing code."
                    )
                }
                is Result.Loading -> {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }
            }
        }
    }
}

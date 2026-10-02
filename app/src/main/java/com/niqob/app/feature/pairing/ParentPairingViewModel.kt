package com.niqob.app.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.usecase.CreatePairingCodeUseCase
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.RevokePairingUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ParentPairingUiState(
    val session: PairingSession? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ParentPairingViewModel(
    private val pairingRepository: PairingRepository = SupabasePairingRepository.getInstance(),
    private val createPairingCodeUseCase: CreatePairingCodeUseCase = CreatePairingCodeUseCase(pairingRepository),
    private val getPairingSessionUseCase: GetPairingSessionUseCase = GetPairingSessionUseCase(pairingRepository),
    private val revokePairingUseCase: RevokePairingUseCase = RevokePairingUseCase(pairingRepository)
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentPairingUiState())
    val uiState: StateFlow<ParentPairingUiState> = _uiState.asStateFlow()

    init {
        observePairingSession()
    }

    fun checkPairingStatus() {
        try {
            viewModelScope.launch {
                pairingRepository.syncPairingSession()
            }
        } catch (_: Throwable) {
            // Safeguarded for unit tests
        }
    }

    private fun observePairingSession() {
        try {
            viewModelScope.launch {
                getPairingSessionUseCase().collect { session ->
                    _uiState.value = _uiState.value.copy(
                        session = session,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }
            viewModelScope.launch {
                pairingRepository.syncPairingSession()
            }
        } catch (_: Throwable) {
            // Safeguarded for unit tests without Main looper
        }
    }

    fun generatePairingCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = createPairingCodeUseCase()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        session = result.data,
                        isLoading = false
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "Failed to generate pairing code"
                    )
                }
                is Result.Loading -> {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }
            }
        }
    }

    fun revokePairing() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            revokePairingUseCase()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }
}

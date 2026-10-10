package com.niqob.app.feature.parent.override

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.data.repository.SupabaseRemoteOverrideRepository
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.repository.RemoteOverrideRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ParentRemoteOverrideUiState(
    val isLoading: Boolean = false,
    val pendingRequests: List<RemoteOverrideRequest> = emptyList(),
    val actionInProgressRequestId: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val targetDeviceId: String? = null
) {
    val isPerformingAction: Boolean get() = actionInProgressRequestId != null
}

class ParentRemoteOverrideViewModel(
    private val remoteOverrideRepository: RemoteOverrideRepository = SupabaseRemoteOverrideRepository(),
    private val pairingRepository: PairingRepository = SupabasePairingRepository.getInstance(),
    initialDeviceId: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ParentRemoteOverrideUiState(targetDeviceId = initialDeviceId)
    )
    val uiState: StateFlow<ParentRemoteOverrideUiState> = _uiState.asStateFlow()

    init {
        if (initialDeviceId != null) {
            loadPendingRequests(initialDeviceId)
        } else {
            resolveConnectedDeviceIdAndLoad()
        }
    }

    private fun resolveConnectedDeviceIdAndLoad() {
        try {
            viewModelScope.launch(dispatcher) {
                pairingRepository.getPairingSession().collect { session ->
                    val childId = session?.childDeviceId
                    _uiState.update { it.copy(targetDeviceId = childId) }
                    loadPendingRequests(childId)
                }
            }
        } catch (_: Throwable) {
            // For unit tests without Looper
            loadPendingRequests(null)
        }
    }

    fun loadPendingRequests(deviceId: String? = _uiState.value.targetDeviceId) {
        try {
            viewModelScope.launch(dispatcher) {
                loadPendingRequestsInternal(deviceId)
            }
        } catch (_: Throwable) {
            // Fallback for non-coroutine test runners
        }
    }

    suspend fun loadPendingRequestsInternal(
        deviceId: String? = _uiState.value.targetDeviceId
    ): Result<List<RemoteOverrideRequest>> {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val result = remoteOverrideRepository.getPendingRequests(deviceId)
        when (result) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingRequests = result.data,
                        errorMessage = null
                    )
                }
            }
            is Result.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "Failed to load pending requests."
                    )
                }
            }
            is Result.Loading -> { /* Handled via isLoading flag */ }
        }
        return result
    }

    fun approveRequest(requestId: String, approvedDurationMinutes: Int? = null) {
        try {
            viewModelScope.launch(dispatcher) {
                approveRequestInternal(requestId, approvedDurationMinutes)
            }
        } catch (_: Throwable) {
            // Fallback for non-coroutine test runners
        }
    }

    suspend fun approveRequestInternal(
        requestId: String,
        approvedDurationMinutes: Int? = null
    ): Result<RemoteOverrideRequest> {
        val currentState = _uiState.value
        if (currentState.actionInProgressRequestId != null) {
            return Result.Error(IllegalStateException("Another action is already in progress."))
        }

        if (approvedDurationMinutes != null &&
            approvedDurationMinutes !in RemoteOverrideRequest.VALID_DURATIONS_MINUTES
        ) {
            val errorMsg = "Approved duration must be 15, 30, or 60 minutes."
            _uiState.update { it.copy(errorMessage = errorMsg) }
            return Result.Error(IllegalArgumentException(errorMsg))
        }

        _uiState.update {
            it.copy(actionInProgressRequestId = requestId, errorMessage = null, successMessage = null)
        }

        val result = remoteOverrideRepository.approveRequest(requestId, approvedDurationMinutes)
        when (result) {
            is Result.Success -> {
                _uiState.update { state ->
                    state.copy(
                        actionInProgressRequestId = null,
                        pendingRequests = state.pendingRequests.filterNot { it.id == requestId },
                        successMessage = "Screen time request approved.",
                        errorMessage = null
                    )
                }
            }
            is Result.Error -> {
                _uiState.update {
                    it.copy(
                        actionInProgressRequestId = null,
                        errorMessage = result.message ?: "Failed to approve request."
                    )
                }
            }
            is Result.Loading -> { /* Handled via actionInProgressRequestId */ }
        }
        return result
    }

    fun rejectRequest(requestId: String, rejectionReason: String? = null) {
        try {
            viewModelScope.launch(dispatcher) {
                rejectRequestInternal(requestId, rejectionReason)
            }
        } catch (_: Throwable) {
            // Fallback for non-coroutine test runners
        }
    }

    suspend fun rejectRequestInternal(
        requestId: String,
        rejectionReason: String? = null
    ): Result<RemoteOverrideRequest> {
        val currentState = _uiState.value
        if (currentState.actionInProgressRequestId != null) {
            return Result.Error(IllegalStateException("Another action is already in progress."))
        }

        val reasonText = rejectionReason?.trim()?.ifBlank { null }
        if (reasonText != null && reasonText.length > RemoteOverrideRequest.MAX_REASON_LENGTH) {
            val errorMsg = "Rejection note cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters."
            _uiState.update { it.copy(errorMessage = errorMsg) }
            return Result.Error(IllegalArgumentException(errorMsg))
        }

        _uiState.update {
            it.copy(actionInProgressRequestId = requestId, errorMessage = null, successMessage = null)
        }

        val result = remoteOverrideRepository.rejectRequest(requestId, reasonText)
        when (result) {
            is Result.Success -> {
                _uiState.update { state ->
                    state.copy(
                        actionInProgressRequestId = null,
                        pendingRequests = state.pendingRequests.filterNot { it.id == requestId },
                        successMessage = "Screen time request rejected.",
                        errorMessage = null
                    )
                }
            }
            is Result.Error -> {
                _uiState.update {
                    it.copy(
                        actionInProgressRequestId = null,
                        errorMessage = result.message ?: "Failed to reject request."
                    )
                }
            }
            is Result.Loading -> { /* Handled via actionInProgressRequestId */ }
        }
        return result
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}

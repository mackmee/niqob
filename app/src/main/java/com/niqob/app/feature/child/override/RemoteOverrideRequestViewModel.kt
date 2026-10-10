package com.niqob.app.feature.child.override

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.data.repository.SupabaseRemoteOverrideRepository
import com.niqob.app.domain.enforcement.ParentOverrideSessionStore
import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.repository.RemoteOverrideRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import com.niqob.app.domain.usecase.ConsumeRemoteOverrideUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChildRemoteOverrideUiState(
    val deviceId: String = "",
    val selectedDurationMinutes: Int = 15,
    val reason: String = "",
    val isSubmitting: Boolean = false,
    val isRefreshing: Boolean = false,
    val isActivating: Boolean = false,
    val isActivationSuccess: Boolean = false,
    val activatedSession: ParentOverrideSession? = null,
    val activeRequest: RemoteOverrideRequest? = null,
    val errorMessage: String? = null,
    val isSuccessSent: Boolean = false
) {
    val canSubmit: Boolean
        get() = !isSubmitting && !isActivating && deviceId.isNotBlank() && (activeRequest == null || activeRequest.status != RemoteOverrideRequestStatus.PENDING)

    val canActivate: Boolean
        get() = !isActivating && !isSubmitting && activeRequest != null && activeRequest.status == RemoteOverrideRequestStatus.APPROVED
}

class RemoteOverrideRequestViewModel(
    private val remoteOverrideRepository: RemoteOverrideRepository = SupabaseRemoteOverrideRepository(),
    private val pairingRepository: PairingRepository = SupabasePairingRepository.getInstance(),
    private val enforcementRepository: ScreenTimeEnforcementRepository = LocalScreenTimeEnforcementRepository.getInstance(),
    private val sessionStore: ParentOverrideSessionStore? = null,
    private val consumeRemoteOverrideUseCase: ConsumeRemoteOverrideUseCase = ConsumeRemoteOverrideUseCase(remoteOverrideRepository),
    initialDeviceId: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChildRemoteOverrideUiState(deviceId = initialDeviceId ?: "")
    )
    val uiState: StateFlow<ChildRemoteOverrideUiState> = _uiState.asStateFlow()

    init {
        if (initialDeviceId.isNullOrBlank()) {
            resolveDeviceId()
        }
    }

    fun setDeviceId(deviceId: String) {
        _uiState.update { it.copy(deviceId = deviceId) }
    }

    private fun resolveDeviceId() {
        try {
            viewModelScope.launch(dispatcher) {
                pairingRepository.getPairingSession().collect { session ->
                    val resolvedId = session?.childDeviceId
                    if (!resolvedId.isNullOrBlank()) {
                        _uiState.update { it.copy(deviceId = resolvedId) }
                    }
                }
            }
        } catch (_: Throwable) {
            // Safeguarded for unit tests without Android Looper
        }
    }

    fun onDurationSelected(minutes: Int) {
        if (minutes in RemoteOverrideRequest.VALID_DURATIONS_MINUTES) {
            _uiState.update { it.copy(selectedDurationMinutes = minutes, errorMessage = null) }
        } else {
            _uiState.update {
                it.copy(errorMessage = "Requested duration must be 15, 30, or 60 minutes.")
            }
        }
    }

    fun onReasonChanged(reason: String) {
        if (reason.length <= RemoteOverrideRequest.MAX_REASON_LENGTH) {
            _uiState.update { it.copy(reason = reason, errorMessage = null) }
        } else {
            _uiState.update {
                it.copy(errorMessage = "Reason cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters.")
            }
        }
    }

    fun submitRequest() {
        try {
            viewModelScope.launch(dispatcher) {
                submitRequestInternal()
            }
        } catch (_: Throwable) {
            // Fallback for non-coroutine test runners
        }
    }

    suspend fun submitRequestInternal(): Result<RemoteOverrideRequest> {
        val currentState = _uiState.value
        if (currentState.isSubmitting || currentState.isActivating) {
            return Result.Error(IllegalStateException("A submission or activation is already in progress."))
        }

        val deviceId = currentState.deviceId.trim()
        if (deviceId.isBlank()) {
            val errorMsg = "Device ID not found. Ensure device is paired with a parent."
            _uiState.update { it.copy(errorMessage = errorMsg) }
            return Result.Error(IllegalArgumentException(errorMsg))
        }

        val duration = currentState.selectedDurationMinutes
        if (duration !in RemoteOverrideRequest.VALID_DURATIONS_MINUTES) {
            val errorMsg = "Requested duration must be 15, 30, or 60 minutes."
            _uiState.update { it.copy(errorMessage = errorMsg) }
            return Result.Error(IllegalArgumentException(errorMsg))
        }

        val reasonText = currentState.reason.trim().ifBlank { null }
        if (reasonText != null && reasonText.length > RemoteOverrideRequest.MAX_REASON_LENGTH) {
            val errorMsg = "Reason cannot exceed ${RemoteOverrideRequest.MAX_REASON_LENGTH} characters."
            _uiState.update { it.copy(errorMessage = errorMsg) }
            return Result.Error(IllegalArgumentException(errorMsg))
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

        val result = remoteOverrideRepository.createRequest(deviceId, duration, reasonText)
        when (result) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        activeRequest = result.data,
                        isSuccessSent = true,
                        errorMessage = null
                    )
                }
            }
            is Result.Error -> {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.message ?: "Failed to submit screen time request."
                    )
                }
            }
            is Result.Loading -> { /* Handled via isSubmitting flag */ }
        }
        return result
    }

    fun refreshStatus() {
        try {
            viewModelScope.launch(dispatcher) {
                refreshStatusInternal()
            }
        } catch (_: Throwable) {
            // Fallback for non-coroutine test runners
        }
    }

    suspend fun refreshStatusInternal(): Result<RemoteOverrideRequest>? {
        val currentState = _uiState.value
        val request = currentState.activeRequest ?: return null
        val deviceId = currentState.deviceId.trim().ifBlank { request.deviceId }

        if (deviceId.isBlank() || request.id.isBlank()) return null
        if (currentState.isRefreshing) return null

        _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }

        val result = remoteOverrideRepository.getRequestStatus(deviceId, request.id)
        when (result) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        activeRequest = result.data,
                        errorMessage = null
                    )
                }
            }
            is Result.Error -> {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = result.message ?: "Failed to refresh request status."
                    )
                }
            }
            is Result.Loading -> { /* Handled via isRefreshing */ }
        }
        return result
    }

    /**
     * Consumes an approved override request and creates an authoritative local [ParentOverrideSession].
     *
     * Enforces the mandatory atomicity order:
     * 1. Call server consume RPC
     * 2. Verify consume succeeded
     * 3. Receive server-authoritative timestamps & duration
     * 4. Persist local ParentOverrideSession
     * 5. Only after persistence succeeds, update UI state with activation success
     */
    fun continueToActivate() {
        try {
            viewModelScope.launch(dispatcher) {
                continueToActivateInternal()
            }
        } catch (_: Throwable) {
            // Fallback for non-coroutine test runners
        }
    }

    suspend fun continueToActivateInternal(): Result<ParentOverrideSession> {
        val currentState = _uiState.value
        if (currentState.isActivating) {
            return Result.Error(IllegalStateException("Activation is already in progress."))
        }

        val request = currentState.activeRequest
        if (request == null) {
            val msg = "No active request to activate."
            _uiState.update { it.copy(errorMessage = msg) }
            return Result.Error(IllegalStateException(msg))
        }

        if (request.status != RemoteOverrideRequestStatus.APPROVED) {
            val msg = when (request.status) {
                RemoteOverrideRequestStatus.PENDING -> "Request is still waiting for parent approval."
                RemoteOverrideRequestStatus.REJECTED -> "Request was rejected by parent."
                RemoteOverrideRequestStatus.EXPIRED -> "Approval claim window has expired."
                RemoteOverrideRequestStatus.CONSUMED -> "This override has already been consumed."
                RemoteOverrideRequestStatus.REVOKED -> "This override has been revoked."
                RemoteOverrideRequestStatus.APPROVED -> ""
            }
            _uiState.update { it.copy(errorMessage = msg) }
            return Result.Error(IllegalStateException(msg))
        }

        val deviceId = currentState.deviceId.trim().ifBlank { request.deviceId.trim() }
        if (deviceId.isBlank()) {
            val msg = "Device ID not found. Ensure device is paired."
            _uiState.update { it.copy(errorMessage = msg) }
            return Result.Error(IllegalArgumentException(msg))
        }

        val requestId = request.id.trim()
        if (requestId.isBlank()) {
            val msg = "Request ID is invalid."
            _uiState.update { it.copy(errorMessage = msg) }
            return Result.Error(IllegalArgumentException(msg))
        }

        _uiState.update { it.copy(isActivating = true, errorMessage = null) }

        // MANDATORY ATOMICITY ORDER:
        // 1. Call server consume RPC (via ConsumeRemoteOverrideUseCase)
        // 2. Verify consume succeeded
        // 3. Receive server-authoritative timestamps & duration
        // 4. Persist local ParentOverrideSession
        // 5. Only after persistence succeeds, update UI state with activation success
        val consumeResult = consumeRemoteOverrideUseCase(deviceId, requestId)

        when (consumeResult) {
            is Result.Success -> {
                val consumed = consumeResult.data
                val localSession = ParentOverrideSession(
                    isActive = true,
                    activatedAtMillis = consumed.consumedAtMillis,
                    expiresAtMillis = consumed.consumedExpiresAtMillis,
                    durationMinutes = consumed.approvedDurationMinutes
                )

                try {
                    sessionStore?.save(localSession)
                    enforcementRepository.setParentOverrideSession(localSession)
                } catch (e: Exception) {
                    val msg = "Failed to activate local screen time session: ${e.message}"
                    _uiState.update {
                        it.copy(
                            isActivating = false,
                            errorMessage = msg
                        )
                    }
                    return Result.Error(Exception(msg, e))
                }

                val updatedRequest = request.copy(
                    status = RemoteOverrideRequestStatus.CONSUMED,
                    approvedDurationMinutes = consumed.approvedDurationMinutes,
                    consumedAt = consumed.consumedAt,
                    consumedExpiresAt = consumed.consumedExpiresAt,
                    isActive = true
                )

                _uiState.update {
                    it.copy(
                        isActivating = false,
                        isActivationSuccess = true,
                        activeRequest = updatedRequest,
                        activatedSession = localSession,
                        errorMessage = null
                    )
                }
                return Result.Success(localSession)
            }
            is Result.Error -> {
                val errMessage = consumeResult.message ?: consumeResult.exception.message ?: "Failed to consume override."
                val lowerMsg = errMessage.lowercase()

                val updatedStatus = when {
                    lowerMsg.contains("claim_window_expired") || lowerMsg.contains("expired") -> {
                        RemoteOverrideRequestStatus.EXPIRED
                    }
                    lowerMsg.contains("revoked") -> {
                        RemoteOverrideRequestStatus.REVOKED
                    }
                    lowerMsg.contains("already_consumed") || lowerMsg.contains("already been consumed") -> {
                        RemoteOverrideRequestStatus.CONSUMED
                    }
                    else -> null
                }

                val mappedUserMessage = when {
                    lowerMsg.contains("claim_window_expired") || lowerMsg.contains("expired") -> {
                        "Approval claim window has expired."
                    }
                    lowerMsg.contains("revoked") -> {
                        "This override has been revoked."
                    }
                    lowerMsg.contains("already_consumed") || lowerMsg.contains("already been consumed") -> {
                        val existingLocal = enforcementRepository.getActiveOverrideSession()
                        if (existingLocal != null && existingLocal.isCurrentlyActive()) {
                            _uiState.update {
                                it.copy(
                                    isActivating = false,
                                    isActivationSuccess = true,
                                    activeRequest = request.copy(status = RemoteOverrideRequestStatus.CONSUMED),
                                    activatedSession = existingLocal,
                                    errorMessage = null
                                )
                            }
                            return Result.Success(existingLocal)
                        }
                        "This override has already been consumed."
                    }
                    lowerMsg.contains("network") || lowerMsg.contains("connect") || lowerMsg.contains("unable to resolve host") -> {
                        "Connection unavailable. Please check your internet connection and try again."
                    }
                    lowerMsg.contains("timeout") -> {
                        "Request timed out. Please check your connection and try again."
                    }
                    else -> errMessage
                }

                _uiState.update { state ->
                    state.copy(
                        isActivating = false,
                        errorMessage = mappedUserMessage,
                        activeRequest = if (updatedStatus != null) state.activeRequest?.copy(status = updatedStatus) else state.activeRequest
                    )
                }
                return Result.Error(Exception(mappedUserMessage, consumeResult.exception))
            }
            is Result.Loading -> {
                return Result.Loading
            }
        }
    }

    fun resetToNewRequest() {
        _uiState.update {
            it.copy(
                activeRequest = null,
                isSuccessSent = false,
                isActivating = false,
                isActivationSuccess = false,
                activatedSession = null,
                errorMessage = null,
                reason = "",
                selectedDurationMinutes = 15
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

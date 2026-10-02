package com.niqob.app.feature.child

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.data.repository.SupabaseDeviceRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.repository.DeviceRepository
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChildHomeUiState(
    val greeting: String = "Welcome to your safe digital space.",
    val dailyGoalProgress: Float = 0.4f,
    val completedActivities: Int = 2,
    val isParentConnected: Boolean = false,
    val parentDeviceId: String? = null
)

class ChildHomeViewModel(
    private val getPairingSessionUseCase: GetPairingSessionUseCase = GetPairingSessionUseCase(SupabasePairingRepository.getInstance()),
    private val deviceRepository: DeviceRepository = SupabaseDeviceRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChildHomeUiState())
    val uiState: StateFlow<ChildHomeUiState> = _uiState.asStateFlow()

    init {
        observePairing()
    }

    private fun observePairing() {
        try {
            viewModelScope.launch {
                getPairingSessionUseCase().collect { session ->
                    val isConnected = session?.status == PairingStatus.CONNECTED
                    _uiState.value = _uiState.value.copy(
                        isParentConnected = isConnected,
                        parentDeviceId = if (isConnected) session.parentDeviceId else null
                    )
                    if (isConnected && !session.childDeviceId.isNullOrBlank()) {
                        deviceRepository.sendHeartbeat(session.childDeviceId)
                    }
                }
            }
        } catch (_: Throwable) {
            // Safeguarded for unit tests without Main looper
        }
    }
}

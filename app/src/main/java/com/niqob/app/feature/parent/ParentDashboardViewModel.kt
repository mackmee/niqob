package com.niqob.app.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.NiqobRepositoryImpl
import com.niqob.app.data.repository.SupabaseAuthRepository
import com.niqob.app.data.repository.SupabaseDeviceRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.User
import com.niqob.app.domain.repository.AuthRepository
import com.niqob.app.domain.repository.AuthState
import com.niqob.app.domain.repository.DeviceRepository
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.usecase.GetChildDeviceStatusUseCase
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.ParentLogoutUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ParentDashboardUiState(
    val user: User? = null,
    val childStatus: ChildDeviceStatus = ChildDeviceStatus(
        isConnected = false,
        deviceName = "Not connected",
        screenTimeMinutes = 0,
        allowedAppsCount = 0,
        safetyAlertsCount = 0
    ),
    val pairingSession: PairingSession? = null,
    val connectivityStatus: DeviceConnectivityStatus = DeviceConnectivityStatus.OFFLINE,
    val lastSeenAt: Long = 0L,
    val isLoading: Boolean = false
)

class ParentDashboardViewModel(
    private val getChildDeviceStatusUseCase: GetChildDeviceStatusUseCase = GetChildDeviceStatusUseCase(
        NiqobRepositoryImpl()
    ),
    private val pairingRepository: PairingRepository = SupabasePairingRepository.getInstance(),
    private val getPairingSessionUseCase: GetPairingSessionUseCase = GetPairingSessionUseCase(pairingRepository),
    private val authRepository: AuthRepository = SupabaseAuthRepository(),
    private val deviceRepository: DeviceRepository = SupabaseDeviceRepository(),
    private val logoutUseCase: ParentLogoutUseCase = ParentLogoutUseCase(authRepository)
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentDashboardUiState(user = authRepository.getCurrentUser()))
    val uiState: StateFlow<ParentDashboardUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun refreshDashboard() {
        try {
            viewModelScope.launch {
                syncRemoteData()
            }
        } catch (_: Throwable) {
            // Safeguarded for unit tests without Main looper
        }
    }

    private fun loadData() {
        try {
            viewModelScope.launch {
                authRepository.authState.collect { authState ->
                    if (authState is AuthState.Authenticated) {
                        _uiState.value = _uiState.value.copy(user = authState.user)
                        syncRemoteData()
                    } else if (authState is AuthState.Unauthenticated) {
                        _uiState.value = _uiState.value.copy(user = null)
                    }
                }
            }
            viewModelScope.launch {
                getChildDeviceStatusUseCase().collect { status ->
                    if (!_uiState.value.childStatus.isConnected) {
                        _uiState.value = _uiState.value.copy(
                            childStatus = status,
                            isLoading = false
                        )
                    }
                }
            }
            viewModelScope.launch {
                getPairingSessionUseCase().collect { session ->
                    handleSessionUpdate(session)
                }
            }
            viewModelScope.launch {
                deviceRepository.getDevicesForFamily("").collect { devices ->
                    val childDevice = devices.firstOrNull { it.deviceType == "CHILD_PHONE" } ?: devices.firstOrNull()
                    if (childDevice != null) {
                        val lastSeen = childDevice.lastSeenAt
                        val connectivity = deviceRepository.calculateDeviceConnectivity(lastSeen)
                        _uiState.value = _uiState.value.copy(
                            connectivityStatus = connectivity,
                            lastSeenAt = lastSeen,
                            childStatus = ChildDeviceStatus(
                                isConnected = true,
                                deviceName = childDevice.deviceName.ifBlank { "Child Device" },
                                screenTimeMinutes = _uiState.value.childStatus.screenTimeMinutes,
                                allowedAppsCount = _uiState.value.childStatus.allowedAppsCount,
                                safetyAlertsCount = _uiState.value.childStatus.safetyAlertsCount
                            )
                        )
                    }
                }
            }
            viewModelScope.launch {
                syncRemoteData()
            }
        } catch (_: Throwable) {
            // In unit tests without an Android Main Looper, the default initial state is retained
        }
    }

    private suspend fun syncRemoteData() {
        try {
            pairingRepository.syncPairingSession()
            val deviceResult = deviceRepository.fetchDevices()
            val devices: List<Device> = (deviceResult as? Result.Success<List<Device>>)?.data ?: emptyList()
            val childDevice = devices.firstOrNull { it.deviceType == "CHILD_PHONE" } ?: devices.firstOrNull()

            val session = (pairingRepository.getPairingSession() as? StateFlow)?.value ?: _uiState.value.pairingSession
            val isConnected = session?.status == PairingStatus.CONNECTED || childDevice != null

            if (isConnected) {
                val lastSeen = childDevice?.lastSeenAt ?: (session?.connectedAt ?: session?.createdAt ?: System.currentTimeMillis())
                val connectivity = deviceRepository.calculateDeviceConnectivity(lastSeen)
                val deviceName = childDevice?.deviceName ?: (session?.childDeviceId ?: "Child Device")

                val effectiveSession = session?.copy(
                    status = PairingStatus.CONNECTED,
                    childDeviceId = session.childDeviceId ?: childDevice?.id
                ) ?: PairingSession(
                    pairingId = childDevice?.id ?: "connected_session",
                    parentDeviceId = "parent_cloud",
                    childDeviceId = childDevice?.id,
                    pairingCode = "",
                    createdAt = childDevice?.lastSeenAt ?: System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L,
                    status = PairingStatus.CONNECTED,
                    familyId = childDevice?.familyId,
                    connectedAt = childDevice?.lastSeenAt
                )

                _uiState.value = _uiState.value.copy(
                    pairingSession = effectiveSession,
                    connectivityStatus = connectivity,
                    lastSeenAt = lastSeen,
                    childStatus = ChildDeviceStatus(
                        isConnected = true,
                        deviceName = deviceName.ifBlank { "Child Device" },
                        screenTimeMinutes = _uiState.value.childStatus.screenTimeMinutes,
                        allowedAppsCount = _uiState.value.childStatus.allowedAppsCount,
                        safetyAlertsCount = _uiState.value.childStatus.safetyAlertsCount
                    ),
                    isLoading = false
                )
            }
        } catch (_: Throwable) {
            // Best effort remote sync
        }
    }

    private fun handleSessionUpdate(session: PairingSession?) {
        val isConnected = session?.status == PairingStatus.CONNECTED
        val lastSeen = if (isConnected) {
            session.connectedAt ?: session.createdAt
        } else {
            _uiState.value.lastSeenAt
        }
        val connectivity = if (isConnected) {
            deviceRepository.calculateDeviceConnectivity(lastSeen)
        } else {
            _uiState.value.connectivityStatus
        }

        _uiState.value = _uiState.value.copy(
            pairingSession = session ?: _uiState.value.pairingSession,
            connectivityStatus = if (isConnected) connectivity else _uiState.value.connectivityStatus,
            lastSeenAt = if (isConnected) lastSeen else _uiState.value.lastSeenAt,
            childStatus = if (isConnected) {
                ChildDeviceStatus(
                    isConnected = true,
                    deviceName = session.childDeviceId ?: "Child Device",
                    screenTimeMinutes = _uiState.value.childStatus.screenTimeMinutes,
                    allowedAppsCount = _uiState.value.childStatus.allowedAppsCount,
                    safetyAlertsCount = _uiState.value.childStatus.safetyAlertsCount
                )
            } else {
                _uiState.value.childStatus
            }
        )
    }

    fun logout(onLoggedOut: () -> Unit) {
        try {
            viewModelScope.launch {
                logoutUseCase()
                onLoggedOut()
            }
        } catch (_: Throwable) {
            onLoggedOut()
        }
    }
}

package com.niqob.app.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.repository.NiqobRepositoryImpl
import com.niqob.app.data.repository.SupabaseAuthRepository
import com.niqob.app.data.repository.SupabaseDeviceRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.data.repository.SupabaseScreenTimeRepository
import com.niqob.app.data.repository.SupabaseScreenTimeUsageRepository
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.domain.model.ScreenTimeLimit
import com.niqob.app.domain.model.User
import com.niqob.app.domain.repository.AuthRepository
import com.niqob.app.domain.repository.AuthState
import com.niqob.app.domain.repository.DeviceRepository
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.repository.ScreenTimeRepository
import com.niqob.app.domain.repository.ScreenTimeUsageRepository
import com.niqob.app.domain.usecase.GetChildDeviceStatusUseCase
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.ParentLogoutUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val isLoading: Boolean = false,
    val childDeviceId: String? = null,
    val screenTimeLimitMinutes: Int = 120,
    val selectedScreenTimeMinutes: Int = 120,
    val isScreenTimeEnabled: Boolean = true,
    val isScreenTimeLoading: Boolean = false,
    val isScreenTimeSaving: Boolean = false,
    val screenTimeError: String? = null,
    val screenTimeSaveSuccess: Boolean = false,
    val todayUsage: ScreenTimeDailyUsage? = null,
    val isUsageLoading: Boolean = false,
    val usageError: String? = null
)

class ParentDashboardViewModel(
    private val getChildDeviceStatusUseCase: GetChildDeviceStatusUseCase = GetChildDeviceStatusUseCase(
        NiqobRepositoryImpl()
    ),
    private val pairingRepository: PairingRepository = SupabasePairingRepository.getInstance(),
    private val getPairingSessionUseCase: GetPairingSessionUseCase = GetPairingSessionUseCase(pairingRepository),
    private val authRepository: AuthRepository = SupabaseAuthRepository(),
    private val deviceRepository: DeviceRepository = SupabaseDeviceRepository(),
    private val logoutUseCase: ParentLogoutUseCase = ParentLogoutUseCase(authRepository),
    private val screenTimeRepository: ScreenTimeRepository = SupabaseScreenTimeRepository(),
    private val screenTimeUsageRepository: ScreenTimeUsageRepository = SupabaseScreenTimeUsageRepository()
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
                            childDeviceId = childDevice.id,
                            childStatus = ChildDeviceStatus(
                                isConnected = true,
                                deviceName = childDevice.deviceName.ifBlank { "Child Device" },
                                screenTimeMinutes = _uiState.value.screenTimeLimitMinutes,
                                allowedAppsCount = _uiState.value.childStatus.allowedAppsCount,
                                safetyAlertsCount = _uiState.value.childStatus.safetyAlertsCount
                            )
                        )
                        loadScreenTimeLimitInternal(childDevice.id)
                        loadTodayUsageInternal(childDevice.id)
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
            val resolvedChildDeviceId = childDevice?.id ?: session?.childDeviceId

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
                    childDeviceId = resolvedChildDeviceId,
                    childStatus = ChildDeviceStatus(
                        isConnected = true,
                        deviceName = deviceName.ifBlank { "Child Device" },
                        screenTimeMinutes = _uiState.value.screenTimeLimitMinutes,
                        allowedAppsCount = _uiState.value.childStatus.allowedAppsCount,
                        safetyAlertsCount = _uiState.value.childStatus.safetyAlertsCount
                    ),
                    isLoading = false
                )

                if (resolvedChildDeviceId != null) {
                    loadScreenTimeLimitInternal(resolvedChildDeviceId)
                    loadTodayUsageInternal(resolvedChildDeviceId)
                }
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

        val resolvedChildDeviceId = session?.childDeviceId ?: _uiState.value.childDeviceId

        _uiState.value = _uiState.value.copy(
            pairingSession = session ?: _uiState.value.pairingSession,
            connectivityStatus = if (isConnected) connectivity else _uiState.value.connectivityStatus,
            lastSeenAt = if (isConnected) lastSeen else _uiState.value.lastSeenAt,
            childDeviceId = resolvedChildDeviceId,
            childStatus = if (isConnected) {
                ChildDeviceStatus(
                    isConnected = true,
                    deviceName = session?.childDeviceId ?: "Child Device",
                    screenTimeMinutes = _uiState.value.screenTimeLimitMinutes,
                    allowedAppsCount = _uiState.value.childStatus.allowedAppsCount,
                    safetyAlertsCount = _uiState.value.childStatus.safetyAlertsCount
                )
            } else {
                _uiState.value.childStatus
            }
        )

        if (isConnected && resolvedChildDeviceId != null && !_uiState.value.isScreenTimeLoading) {
            try {
                viewModelScope.launch {
                    loadScreenTimeLimitInternal(resolvedChildDeviceId)
                    loadTodayUsageInternal(resolvedChildDeviceId)
                }
            } catch (_: Throwable) {
                // Handled for unit tests without main looper
            }
        }
    }

    fun setTargetChildDeviceId(deviceId: String) {
        _uiState.value = _uiState.value.copy(childDeviceId = deviceId)
    }

    fun loadScreenTimeLimit(targetDeviceId: String? = null) {
        val deviceId = targetDeviceId ?: _uiState.value.childDeviceId ?: return
        try {
            viewModelScope.launch {
                loadScreenTimeLimitInternal(deviceId)
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    suspend fun loadScreenTimeLimitInternal(deviceId: String): Result<ScreenTimeLimit> {
        _uiState.value = _uiState.value.copy(
            isScreenTimeLoading = true,
            screenTimeError = null,
            childDeviceId = deviceId
        )
        val result = screenTimeRepository.getScreenTimeLimit(deviceId)
        when (result) {
            is Result.Success -> {
                val limit = result.data
                _uiState.value = _uiState.value.copy(
                    screenTimeLimitMinutes = limit.dailyLimitMinutes,
                    selectedScreenTimeMinutes = limit.dailyLimitMinutes,
                    isScreenTimeEnabled = limit.enabled,
                    isScreenTimeLoading = false,
                    screenTimeError = null,
                    childStatus = _uiState.value.childStatus.copy(
                        screenTimeMinutes = limit.dailyLimitMinutes
                    )
                )
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isScreenTimeLoading = false,
                    screenTimeError = result.exception.message ?: "Failed to load screen time limit"
                )
            }
            is Result.Loading -> {
                _uiState.value = _uiState.value.copy(isScreenTimeLoading = true)
            }
        }
        return result
    }

    fun loadTodayUsage(targetDeviceId: String? = null) {
        val deviceId = targetDeviceId ?: _uiState.value.childDeviceId ?: return
        try {
            viewModelScope.launch {
                loadTodayUsageInternal(deviceId)
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    suspend fun loadTodayUsageInternal(deviceId: String): Result<ScreenTimeDailyUsage?> {
        _uiState.value = _uiState.value.copy(
            isUsageLoading = true,
            usageError = null,
            childDeviceId = deviceId
        )
        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val result = screenTimeUsageRepository.getDailyUsage(deviceId, todayDateStr)
        when (result) {
            is Result.Success -> {
                _uiState.value = _uiState.value.copy(
                    todayUsage = result.data,
                    isUsageLoading = false,
                    usageError = null
                )
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isUsageLoading = false,
                    usageError = result.exception.message ?: "Failed to load child usage"
                )
            }
            is Result.Loading -> {
                _uiState.value = _uiState.value.copy(isUsageLoading = true)
            }
        }
        return result
    }

    fun updateScreenTimeMinutes(minutes: Int) {
        if (minutes < 0 || minutes > 1440) {
            _uiState.value = _uiState.value.copy(
                selectedScreenTimeMinutes = minutes,
                screenTimeError = "Daily limit must be between 0 and 1440 minutes (24 hours).",
                screenTimeSaveSuccess = false
            )
        } else {
            _uiState.value = _uiState.value.copy(
                selectedScreenTimeMinutes = minutes,
                screenTimeError = null,
                screenTimeSaveSuccess = false
            )
        }
    }

    fun toggleScreenTimeEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            isScreenTimeEnabled = enabled,
            screenTimeSaveSuccess = false
        )
    }

    fun dismissScreenTimeStatus() {
        _uiState.value = _uiState.value.copy(
            screenTimeError = null,
            screenTimeSaveSuccess = false
        )
    }

    fun saveScreenTimeLimit() {
        try {
            viewModelScope.launch {
                saveScreenTimeLimitInternal()
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    suspend fun saveScreenTimeLimitInternal(): Result<ScreenTimeLimit> {
        val deviceId = _uiState.value.childDeviceId
        if (deviceId.isNullOrBlank()) {
            val err = "No child device connected to set screen time limit."
            _uiState.value = _uiState.value.copy(
                screenTimeError = err,
                screenTimeSaveSuccess = false
            )
            return Result.Error(IllegalStateException(err))
        }

        val minutes = _uiState.value.selectedScreenTimeMinutes
        if (minutes < 0 || minutes > 1440) {
            val err = "Daily limit must be between 0 and 1440 minutes."
            _uiState.value = _uiState.value.copy(
                screenTimeError = err,
                screenTimeSaveSuccess = false
            )
            return Result.Error(IllegalArgumentException(err))
        }

        val enabled = _uiState.value.isScreenTimeEnabled

        _uiState.value = _uiState.value.copy(
            isScreenTimeSaving = true,
            screenTimeError = null,
            screenTimeSaveSuccess = false
        )

        // 1. Call set_screen_time_limit
        val setResult = screenTimeRepository.setScreenTimeLimit(
            deviceId = deviceId,
            dailyLimitMinutes = minutes,
            enabled = enabled
        )

        when (setResult) {
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isScreenTimeSaving = false,
                    screenTimeError = setResult.exception.message ?: "Failed to save screen time limit.",
                    screenTimeSaveSuccess = false
                )
                return setResult
            }
            is Result.Loading -> {
                return setResult
            }
            is Result.Success -> {
                // Continue to read back
            }
        }

        // 2. Refresh/read back with get_screen_time_limit
        val getResult = screenTimeRepository.getScreenTimeLimit(deviceId)
        return when (getResult) {
            is Result.Success -> {
                val confirmed = getResult.data
                _uiState.value = _uiState.value.copy(
                    isScreenTimeSaving = false,
                    screenTimeLimitMinutes = confirmed.dailyLimitMinutes,
                    selectedScreenTimeMinutes = confirmed.dailyLimitMinutes,
                    isScreenTimeEnabled = confirmed.enabled,
                    screenTimeSaveSuccess = true,
                    screenTimeError = null,
                    childStatus = _uiState.value.childStatus.copy(
                        screenTimeMinutes = confirmed.dailyLimitMinutes
                    )
                )
                getResult
            }
            is Result.Error, Result.Loading -> {
                val saved = (setResult as Result.Success).data
                _uiState.value = _uiState.value.copy(
                    isScreenTimeSaving = false,
                    screenTimeLimitMinutes = saved.dailyLimitMinutes,
                    selectedScreenTimeMinutes = saved.dailyLimitMinutes,
                    isScreenTimeEnabled = saved.enabled,
                    screenTimeSaveSuccess = true,
                    screenTimeError = null,
                    childStatus = _uiState.value.childStatus.copy(
                        screenTimeMinutes = saved.dailyLimitMinutes
                    )
                )
                setResult
            }
        }
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


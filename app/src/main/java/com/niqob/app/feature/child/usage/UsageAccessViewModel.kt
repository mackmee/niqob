package com.niqob.app.feature.child.usage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niqob.app.core.common.Result
import com.niqob.app.data.local.usage.AndroidUsageStatsDataSource
import com.niqob.app.data.repository.AndroidUsageStatsRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.data.repository.SupabaseScreenTimeRepository
import com.niqob.app.data.repository.SupabaseScreenTimeUsageRepository
import com.niqob.app.domain.calculator.DailyScreenTimeCalculator
import com.niqob.app.domain.model.DailyScreenTimeStatus
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.domain.repository.PairingRepository
import com.niqob.app.domain.repository.ScreenTimeRepository
import com.niqob.app.domain.repository.ScreenTimeUsageRepository
import com.niqob.app.domain.repository.UsageStatsRepository
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.feature.child.usage.sync.ScreenTimeSyncScheduler
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementDecision
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ForegroundAppInfo
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.usecase.EvaluateForegroundAppUseCase
import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.data.local.usage.AndroidForegroundAppDetector
import com.niqob.app.domain.enforcement.AppEnforcementController
import com.niqob.app.domain.enforcement.DeviceOwnerEnforcementManager
import com.niqob.app.domain.model.EnforcementExecutionResult
import com.niqob.app.data.local.enforcement.NiqobAccessibilityEnforcementService
import com.niqob.app.data.local.enforcement.AndroidAppEnforcementController
import com.niqob.app.data.local.enforcement.AndroidDeviceOwnerEnforcementManager
import com.niqob.app.domain.model.AccessibilityEnforcementStatus
import com.niqob.app.data.local.enforcement.AccessibilityServiceStatusHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UsageAccessUiState(
    val isPermissionGranted: Boolean = false,
    val isLoading: Boolean = false,
    val todaySummary: DailyUsageSummary? = null,
    val errorMessage: String? = null,
    val screenTimeLimitMinutes: Int = 120,
    val isScreenTimeEnabled: Boolean = true,
    val isLimitLoading: Boolean = false,
    val limitErrorMessage: String? = null,
    val screenTimeStatus: DailyScreenTimeStatus? = null,
    val enforcementState: ScreenTimeEnforcementState? = null,
    val childDeviceId: String? = null,
    val isSimulatedLimitActive: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncedUsage: ScreenTimeDailyUsage? = null,
    val syncErrorMessage: String? = null,
    val isAutoSyncScheduled: Boolean = false,
    val appPolicyDecisions: List<AppEnforcementDecision> = emptyList(),
    val allowedPackagesInPolicy: Set<String> = emptySet(),
    val detectedForegroundApp: ForegroundAppInfo? = null,
    val foregroundAppDecision: AppEnforcementDecision? = null,
    val isDetectingForegroundApp: Boolean = false,
    val excludeSelfFromDetection: Boolean = true,
    val isEnforcementControllerRunning: Boolean = false,
    val isDeviceOwnerActive: Boolean = false,
    val isAccessibilityServiceConnected: Boolean = false,
    val accessibilityStatus: AccessibilityEnforcementStatus = AccessibilityEnforcementStatus.DISABLED,
    val lastEnforcementExecutionResult: EnforcementExecutionResult? = null
)

class UsageAccessViewModel(
    private val usageStatsRepository: UsageStatsRepository,
    private val screenTimeRepository: ScreenTimeRepository = SupabaseScreenTimeRepository(),
    private val pairingRepository: PairingRepository = SupabasePairingRepository.getInstance(),
    private val screenTimeUsageRepository: ScreenTimeUsageRepository = SupabaseScreenTimeUsageRepository(),
    private val enforcementRepository: ScreenTimeEnforcementRepository = LocalScreenTimeEnforcementRepository.getInstance(),
    private val appPolicyRepository: AppEnforcementPolicyRepository = LocalAppEnforcementPolicyRepository.getInstance(),
    private val evaluateForegroundAppUseCase: EvaluateForegroundAppUseCase? = null,
    private val appEnforcementController: AppEnforcementController? = null,
    private val deviceOwnerManager: DeviceOwnerEnforcementManager? = null,
    private val isAutoSyncScheduledProvider: (() -> Boolean)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageAccessUiState())
    val uiState: StateFlow<UsageAccessUiState> = _uiState.asStateFlow()

    private var serverLimitMinutes: Int = 120
    private var serverLimitEnabled: Boolean = true

    init {
        checkPermissionAndRefresh()
        updatePolicyDecisions()
        try {
            viewModelScope.launch {
                enforcementRepository.getEnforcementState().collect { state ->
                    if (state != null && (state.dailyLimitMinutes != _uiState.value.screenTimeLimitMinutes || state.isEnforcementEnabled != _uiState.value.isScreenTimeEnabled)) {
                        val matchesServer = (state.dailyLimitMinutes == serverLimitMinutes && state.isEnforcementEnabled == serverLimitEnabled)
                        _uiState.value = _uiState.value.copy(
                            screenTimeLimitMinutes = state.dailyLimitMinutes,
                            isScreenTimeEnabled = state.isEnforcementEnabled,
                            isSimulatedLimitActive = !matchesServer,
                            enforcementState = state
                        )
                    }
                    updatePolicyDecisions(enforcement = state)
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without test dispatcher
        }
        try {
            viewModelScope.launch {
                appPolicyRepository.getPolicy().collect { policy ->
                    updatePolicyDecisions(policy = policy)
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without test dispatcher
        }
        try {
            viewModelScope.launch {
                NiqobAccessibilityEnforcementService.isServiceActive.collect { active ->
                    _uiState.value = _uiState.value.copy(isAccessibilityServiceConnected = active)
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without test dispatcher
        }
        try {
            viewModelScope.launch {
                appEnforcementController?.isRunning?.collect { running ->
                    _uiState.value = _uiState.value.copy(isEnforcementControllerRunning = running)
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without test dispatcher
        }
        try {
            viewModelScope.launch {
                NiqobAccessibilityEnforcementService.latestExecutionResult.collect { res ->
                    if (res != null) {
                        _uiState.value = _uiState.value.copy(lastEnforcementExecutionResult = res)
                    }
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without test dispatcher
        }
        _uiState.value = _uiState.value.copy(
            isDeviceOwnerActive = deviceOwnerManager?.isDeviceOwnerActive() ?: false
        )
    }

    fun checkPermissionAndRefresh(excludeSelf: Boolean = true) {
        try {
            viewModelScope.launch {
                checkPermissionAndRefreshSuspend(excludeSelf)
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    suspend fun checkPermissionAndRefreshSuspend(excludeSelf: Boolean = true) {
        val granted = usageStatsRepository.hasUsageAccessPermission()
        if (!granted) {
            enforcementRepository.clear()
            _uiState.value = _uiState.value.copy(
                isPermissionGranted = false,
                isLoading = false,
                todaySummary = null,
                screenTimeStatus = null,
                enforcementState = null,
                errorMessage = null
            )
            return
        }

        val isScheduled = isAutoSyncScheduledProvider?.invoke() ?: false
        _uiState.value = _uiState.value.copy(
            isPermissionGranted = true,
            isLoading = true,
            errorMessage = null,
            isAutoSyncScheduled = isScheduled
        )

        // Sync with active repository enforcement state if changed externally (e.g. gate controls)
        val activeEnforcement = enforcementRepository.getEnforcementState().value
        if (activeEnforcement != null && (activeEnforcement.dailyLimitMinutes != _uiState.value.screenTimeLimitMinutes || activeEnforcement.isEnforcementEnabled != _uiState.value.isScreenTimeEnabled)) {
            val matchesServer = (activeEnforcement.dailyLimitMinutes == serverLimitMinutes && activeEnforcement.isEnforcementEnabled == serverLimitEnabled)
            _uiState.value = _uiState.value.copy(
                screenTimeLimitMinutes = activeEnforcement.dailyLimitMinutes,
                isScreenTimeEnabled = activeEnforcement.isEnforcementEnabled,
                isSimulatedLimitActive = !matchesServer
            )
        }

        // 1. Resolve childDeviceId from pairing session if available
        var resolvedDeviceId = _uiState.value.childDeviceId
        try {
            val sessionFlow = pairingRepository.getPairingSession()
            val session = (sessionFlow as? StateFlow<PairingSession?>)?.value
            if (session?.childDeviceId != null) {
                resolvedDeviceId = session.childDeviceId
                _uiState.value = _uiState.value.copy(childDeviceId = resolvedDeviceId)
            }
        } catch (_: Throwable) {
            // Best effort session check
        }

        // 2. Fetch server screen time limit if device is paired and no simulated limit active
        if (!_uiState.value.isSimulatedLimitActive && resolvedDeviceId != null) {
            _uiState.value = _uiState.value.copy(isLimitLoading = true, limitErrorMessage = null)
            when (val limitResult = screenTimeRepository.getScreenTimeLimit(resolvedDeviceId)) {
                is Result.Success -> {
                    val limit = limitResult.data
                    serverLimitMinutes = limit.dailyLimitMinutes
                    serverLimitEnabled = limit.enabled
                    _uiState.value = _uiState.value.copy(
                        screenTimeLimitMinutes = limit.dailyLimitMinutes,
                        isScreenTimeEnabled = limit.enabled,
                        isLimitLoading = false,
                        limitErrorMessage = null
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLimitLoading = false,
                        limitErrorMessage = limitResult.exception.message
                    )
                }
                is Result.Loading -> {
                    _uiState.value = _uiState.value.copy(isLimitLoading = true)
                }
            }
        }

        // 3. Query today's actual usage
        when (val result = usageStatsRepository.getTodayUsage(excludeSelf = excludeSelf)) {
            is Result.Success -> {
                val summary = result.data
                val calculatedStatus = DailyScreenTimeCalculator.calculate(
                    usedMinutes = summary.totalScreenTimeMinutes,
                    dailyLimitMinutes = _uiState.value.screenTimeLimitMinutes,
                    isEnabled = _uiState.value.isScreenTimeEnabled,
                    date = summary.date
                )
                val enforcement = ScreenTimeEnforcementEvaluator.evaluate(calculatedStatus)
                enforcementRepository.updateEnforcementState(enforcement)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    todaySummary = summary,
                    screenTimeStatus = calculatedStatus,
                    enforcementState = enforcement,
                    errorMessage = null
                )
                updatePolicyDecisions(enforcement = enforcement)
                try {
                    refreshForegroundAppDetectionSuspend()
                } catch (_: Throwable) {
                    // Best effort detection
                }

                // 4. Auto-sync daily aggregate summary to Supabase if paired device is resolved
                if (resolvedDeviceId != null && !_uiState.value.isSimulatedLimitActive) {
                    try {
                        syncTodayUsageSuspend()
                    } catch (_: Throwable) {
                        // Best effort auto sync
                    }
                }
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exception.message ?: "Failed to read today's usage statistics"
                )
            }
            is Result.Loading -> {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
        }
    }

    /**
     * Synchronizes today's aggregate screen-time summary to Supabase.
     */
    fun syncTodayUsage() {
        try {
            viewModelScope.launch {
                syncTodayUsageSuspend()
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    suspend fun syncTodayUsageSuspend(): Result<ScreenTimeDailyUsage> {
        val deviceId = _uiState.value.childDeviceId
        if (deviceId.isNullOrBlank()) {
            val err = "Cannot sync: child device is not paired yet."
            _uiState.value = _uiState.value.copy(syncErrorMessage = err)
            return Result.Error(IllegalStateException(err))
        }

        val summary = _uiState.value.todaySummary
        val status = _uiState.value.screenTimeStatus
        if (summary == null || status == null) {
            val err = "Cannot sync: no usage data calculated yet."
            _uiState.value = _uiState.value.copy(syncErrorMessage = err)
            return Result.Error(IllegalStateException(err))
        }

        _uiState.value = _uiState.value.copy(isSyncing = true, syncErrorMessage = null)

        val result = screenTimeUsageRepository.upsertDailyUsage(
            deviceId = deviceId,
            usageDate = summary.date,
            totalUsageMinutes = status.usedMinutes,
            dailyLimitMinutes = status.dailyLimitMinutes,
            remainingMinutes = status.remainingMinutes,
            usagePercentage = status.usagePercentage,
            isLimitReached = status.isLimitReached
        )

        when (result) {
            is Result.Success -> {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    lastSyncedUsage = result.data,
                    syncErrorMessage = null
                )
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncErrorMessage = result.exception.message ?: "Failed to sync daily usage to cloud."
                )
            }
            is Result.Loading -> {
                _uiState.value = _uiState.value.copy(isSyncing = true)
            }
        }

        return result
    }

    /**
     * For development verification: temporarily apply a test limit value
     * to verify "Limit reached", 0-minute limit, or disabled limit calculation.
     */
    fun setSimulatedLimit(minutes: Int?, enabled: Boolean? = null) {
        val targetMinutes = minutes ?: _uiState.value.screenTimeLimitMinutes
        val targetEnabled = enabled ?: _uiState.value.isScreenTimeEnabled

        val currentSummary = _uiState.value.todaySummary
        val newStatus = currentSummary?.let { summary ->
            DailyScreenTimeCalculator.calculate(
                usedMinutes = summary.totalScreenTimeMinutes,
                dailyLimitMinutes = targetMinutes,
                isEnabled = targetEnabled,
                date = summary.date
            )
        }
        val newEnforcement = newStatus?.let { ScreenTimeEnforcementEvaluator.evaluate(it) }
        if (newEnforcement != null) {
            enforcementRepository.updateEnforcementState(newEnforcement)
        }

        _uiState.value = _uiState.value.copy(
            screenTimeLimitMinutes = targetMinutes,
            isScreenTimeEnabled = targetEnabled,
            isSimulatedLimitActive = true,
            screenTimeStatus = newStatus,
            enforcementState = newEnforcement
        )
        updatePolicyDecisions(enforcement = newEnforcement)
    }

    /**
     * Resets simulated limit back to the server configured limit.
     */
    fun resetToLiveServerLimit() {
        _uiState.value = _uiState.value.copy(
            screenTimeLimitMinutes = serverLimitMinutes,
            isScreenTimeEnabled = serverLimitEnabled,
            isSimulatedLimitActive = false
        )
        checkPermissionAndRefresh()
    }

    /**
     * Re-evaluates test packages against the current policy and enforcement state.
     */
    fun updatePolicyDecisions(
        enforcement: ScreenTimeEnforcementState? = _uiState.value.enforcementState,
        policy: AppEnforcementPolicy = appPolicyRepository.getPolicy().value
    ) {
        val decisions = DEV_VERIFICATION_PACKAGES.map { pkg ->
            AppEnforcementEvaluator.evaluate(
                packageName = pkg,
                enforcementState = enforcement,
                policy = policy
            )
        }
        val detected = _uiState.value.detectedForegroundApp
        val updatedForegroundDecision = if (detected != null) {
            AppEnforcementEvaluator.evaluate(
                packageName = detected.packageName,
                enforcementState = enforcement,
                policy = policy,
                timestampMillis = detected.timestampMillis
            )
        } else {
            _uiState.value.foregroundAppDecision
        }
        _uiState.value = _uiState.value.copy(
            appPolicyDecisions = decisions,
            allowedPackagesInPolicy = policy.allowedPackageNames,
            foregroundAppDecision = updatedForegroundDecision
        )
    }

    /**
     * Toggles an app in the policy's allowed list for development verification.
     */
    fun toggleAllowPackageInPolicy(packageName: String) {
        val currentPolicy = appPolicyRepository.getPolicy().value
        if (currentPolicy.allowedPackageNames.contains(packageName)) {
            appPolicyRepository.removeAllowedPackage(packageName)
        } else {
            appPolicyRepository.addAllowedPackage(packageName)
        }
        updatePolicyDecisions()
    }

    /**
     * Refreshes detection of the most recent foreground application.
     */
    fun refreshForegroundAppDetection() {
        try {
            viewModelScope.launch {
                refreshForegroundAppDetectionSuspend()
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    suspend fun refreshForegroundAppDetectionSuspend() {
        val useCase = evaluateForegroundAppUseCase ?: return
        if (!usageStatsRepository.hasUsageAccessPermission()) {
            _uiState.value = _uiState.value.copy(
                detectedForegroundApp = null,
                foregroundAppDecision = null,
                isDetectingForegroundApp = false
            )
            return
        }

        _uiState.value = _uiState.value.copy(isDetectingForegroundApp = true)
        val result = useCase(excludeSelf = _uiState.value.excludeSelfFromDetection)
        _uiState.value = _uiState.value.copy(
            detectedForegroundApp = result.foregroundApp,
            foregroundAppDecision = result.decision,
            isDetectingForegroundApp = false
        )
    }

    /**
     * Toggles whether NIQOB itself should be excluded when detecting the most recent foreground app.
     */
    fun setExcludeSelfFromDetection(exclude: Boolean) {
        _uiState.value = _uiState.value.copy(excludeSelfFromDetection = exclude)
        refreshForegroundAppDetection()
    }

    /**
     * Starts the app enforcement controller for dev verification.
     */
    fun startEnforcementController() {
        try {
            viewModelScope.launch {
                appEnforcementController?.start()
                val detected = _uiState.value.detectedForegroundApp?.packageName
                if (detected != null) {
                    val res = appEnforcementController?.handleForegroundPackage(detected)
                    _uiState.value = _uiState.value.copy(lastEnforcementExecutionResult = res)
                }
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    /**
     * Stops the app enforcement controller.
     */
    fun stopEnforcementController() {
        try {
            viewModelScope.launch {
                appEnforcementController?.stop()
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    /**
     * Evaluates a specific package using the enforcement controller.
     */
    fun evaluatePackageWithController(packageName: String) {
        try {
            viewModelScope.launch {
                val res = appEnforcementController?.handleForegroundPackage(packageName)
                _uiState.value = _uiState.value.copy(lastEnforcementExecutionResult = res)
            }
        } catch (_: Throwable) {
            // Handled for unit tests without main looper
        }
    }

    /**
     * Refreshes the AccessibilityService status from Android system settings.
     */
    fun refreshAccessibilityStatus(context: Context) {
        try {
            val status = AccessibilityServiceStatusHelper.checkStatus(context)
            _uiState.value = _uiState.value.copy(
                accessibilityStatus = status,
                isAccessibilityServiceConnected = status == AccessibilityEnforcementStatus.ENABLED
            )
        } catch (_: Throwable) {
            // Handled for unit tests without Android Context
        }
    }

    companion object {
        val DEV_VERIFICATION_PACKAGES = listOf(
            "com.niqob.app",
            "com.android.settings",
            "com.android.chrome",
            "com.google.android.youtube"
        )

        fun provideFactory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val dataSource = AndroidUsageStatsDataSource(appContext)
                val usageStatsRepo = AndroidUsageStatsRepository(
                    dataSource = dataSource,
                    appLabelResolver = { pkg ->
                        try {
                            val pm = appContext.packageManager
                            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                pm.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                            } else {
                                @Suppress("DEPRECATION")
                                pm.getApplicationInfo(pkg, 0)
                            }
                            pm.getApplicationLabel(info).toString()
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
                val screenTimeRepo = SupabaseScreenTimeRepository()
                val pairingRepo = SupabasePairingRepository.getInstance(appContext)
                val screenTimeUsageRepo = SupabaseScreenTimeUsageRepository()
                val policyRepo = LocalAppEnforcementPolicyRepository.getInstance()
                val enforcementRepo = LocalScreenTimeEnforcementRepository.getInstance()
                val foregroundDetector = AndroidForegroundAppDetector(appContext)
                val evaluateUseCase = EvaluateForegroundAppUseCase(
                    foregroundAppDetector = foregroundDetector,
                    policyRepository = policyRepo,
                    enforcementRepository = enforcementRepo
                )
                val deviceOwnerManager = AndroidDeviceOwnerEnforcementManager(appContext)
                val enforcementController = AndroidAppEnforcementController(
                    policyRepository = policyRepo,
                    enforcementRepository = enforcementRepo,
                    deviceOwnerManager = deviceOwnerManager
                )
                return UsageAccessViewModel(
                    usageStatsRepository = usageStatsRepo,
                    screenTimeRepository = screenTimeRepo,
                    pairingRepository = pairingRepo,
                    screenTimeUsageRepository = screenTimeUsageRepo,
                    enforcementRepository = enforcementRepo,
                    appPolicyRepository = policyRepo,
                    evaluateForegroundAppUseCase = evaluateUseCase,
                    appEnforcementController = enforcementController,
                    deviceOwnerManager = deviceOwnerManager,
                    isAutoSyncScheduledProvider = { ScreenTimeSyncScheduler.isScheduled(appContext) }
                ) as T
            }
        }
    }
}


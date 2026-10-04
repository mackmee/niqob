package com.niqob.app

import com.niqob.app.domain.model.AppUsageInfo
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.fakes.FakeUsageStatsDataSource
import com.niqob.app.data.local.usage.RawUsageStat
import com.niqob.app.data.repository.AndroidUsageStatsRepository
import com.niqob.app.fakes.FakeUsageStatsRepository
import com.niqob.app.feature.child.usage.UsageAccessViewModel
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.ForegroundAppInfo
import com.niqob.app.domain.usecase.EvaluateForegroundAppUseCase
import com.niqob.app.fakes.FakeForegroundAppDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class UsageAccessViewModelTest {

    private lateinit var fakeRepository: FakeUsageStatsRepository
    private lateinit var viewModel: UsageAccessViewModel

    @Before
    fun setUp() {
        com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository.resetForTesting()
        com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository.resetForTesting()
        fakeRepository = FakeUsageStatsRepository(permissionGranted = false)
        viewModel = UsageAccessViewModel(usageStatsRepository = fakeRepository)
    }

    @org.junit.After
    fun tearDown() {
        com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository.resetForTesting()
        com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository.resetForTesting()
    }

    @Test
    fun initialState_whenPermissionNotGranted_reflectsPermissionDenied() {
        val state = viewModel.uiState.value
        assertFalse(state.isPermissionGranted)
        assertFalse(state.isLoading)
        assertNull(state.todaySummary)
        assertNull(state.errorMessage)
    }

    @Test
    fun checkPermissionAndRefresh_whenPermissionGranted_fetchesUsageData() = runBlocking {
        val app1 = AppUsageInfo(
            packageName = "com.google.android.youtube",
            totalTimeInForegroundMillis = 1_800_000L, // 30 min
            usageDate = "2026-10-03",
            appLabel = "YouTube"
        )
        val app2 = AppUsageInfo(
            packageName = "com.android.chrome",
            totalTimeInForegroundMillis = 900_000L, // 15 min
            usageDate = "2026-10-03",
            appLabel = "Chrome"
        )
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 2_700_000L, // 45 min
            appUsages = listOf(app1, app2)
        )

        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val state = viewModel.uiState.value
        assertTrue(state.isPermissionGranted)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertNotNull(state.todaySummary)

        val summary = state.todaySummary!!
        assertEquals(45, summary.totalScreenTimeMinutes)
        assertEquals(2_700_000L, summary.totalScreenTimeMillis)
        assertEquals(2, summary.appUsages.size)
        assertEquals("YouTube", summary.appUsages[0].appLabel)
        assertEquals("com.google.android.youtube", summary.appUsages[0].packageName)
    }

    @Test
    fun checkPermissionAndRefresh_whenPermissionGranted_butNoUsageData_reflectsEmptySummary() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 0L,
            appUsages = emptyList()
        )

        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val state = viewModel.uiState.value
        assertTrue(state.isPermissionGranted)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertNotNull(state.todaySummary)

        val summary = state.todaySummary!!
        assertEquals(0, summary.totalScreenTimeMinutes)
        assertEquals(0L, summary.totalScreenTimeMillis)
        assertTrue(summary.appUsages.isEmpty())
    }

    @Test
    fun checkPermissionAndRefresh_whenRepositoryFails_setsErrorMessage() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.shouldFail = true
        fakeRepository.failureException = RuntimeException("Device usage provider crashed")

        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val state = viewModel.uiState.value
        assertTrue(state.isPermissionGranted)
        assertFalse(state.isLoading)
        assertEquals("Device usage provider crashed", state.errorMessage)
    }

    @Test
    fun permissionRevocation_updatesStateBackToNotGranted() = runBlocking {
        // First grant
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 600_000L,
            appUsages = emptyList()
        )
        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)
        assertTrue(viewModel.uiState.value.isPermissionGranted)

        // Then revoke
        fakeRepository.permissionGranted = false
        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val state = viewModel.uiState.value
        assertFalse(state.isPermissionGranted)
        assertNull(state.todaySummary)
    }

    @Test
    fun androidUsageStatsRepository_resolvesAppLabelsWithResolver() = runBlocking {
        val fakeDataSource = FakeUsageStatsDataSource(permissionGranted = true)
        fakeDataSource.setStats(
            listOf(
                RawUsageStat(
                    packageName = "com.supercell.brawlstars",
                    totalTimeInForeground = 1_200_000L
                )
            )
        )

        val repoWithLabels = AndroidUsageStatsRepository(
            dataSource = fakeDataSource,
            selfPackageName = "com.niqob.app",
            timeProvider = { 1790951400000L },
            timeZone = TimeZone.getTimeZone("UTC"),
            appLabelResolver = { pkg ->
                if (pkg == "com.supercell.brawlstars") "Brawl Stars" else null
            }
        )

        val result = repoWithLabels.getTodayUsage(excludeSelf = true)
        assertTrue(result is com.niqob.app.core.common.Result.Success)

        val summary = (result as com.niqob.app.core.common.Result.Success).data
        assertEquals(1, summary.appUsages.size)
        assertEquals("Brawl Stars", summary.appUsages[0].appLabel)
        assertEquals("com.supercell.brawlstars", summary.appUsages[0].packageName)
    }

    @Test
    fun checkPermissionAndRefresh_calculatesDailyScreenTimeStatusAccurately() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 2_700_000L, // 45 min
            appUsages = emptyList()
        )

        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val status = viewModel.uiState.value.screenTimeStatus
        assertNotNull(status)
        assertEquals(45, status!!.usedMinutes)
        assertEquals(120, status.dailyLimitMinutes)
        assertEquals(75, status.remainingMinutes)
        assertEquals(38, status.usagePercentage) // 45/120 = 37.5 -> 38%
        assertFalse(status.isLimitReached)
        assertEquals("Within limit", status.statusText)
    }

    @Test
    fun setSimulatedLimit_updatesStatusImmediatelyForVerification() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 2_700_000L, // 45 min
            appUsages = emptyList()
        )
        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        // Test limit override: 30 minutes (usage 45 min -> limit reached)
        viewModel.setSimulatedLimit(30, true)
        val statusOver = viewModel.uiState.value.screenTimeStatus
        assertNotNull(statusOver)
        assertEquals(45, statusOver!!.usedMinutes)
        assertEquals(30, statusOver.dailyLimitMinutes)
        assertEquals(0, statusOver.remainingMinutes)
        assertEquals(150, statusOver.usagePercentage)
        assertTrue(statusOver.isLimitReached)
        assertEquals("Limit reached", statusOver.statusText)

        // Test disable limit
        viewModel.setSimulatedLimit(null, false)
        val statusDisabled = viewModel.uiState.value.screenTimeStatus
        assertNotNull(statusDisabled)
        assertFalse(statusDisabled!!.isLimitReached)
        assertEquals("Screen Time limit disabled", statusDisabled.statusText)
    }

    @Test
    fun syncTodayUsage_whenDeviceNotPaired_failsWithInformativeMessage() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 2_700_000L,
            appUsages = emptyList()
        )
        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val syncResult = viewModel.syncTodayUsageSuspend()
        assertTrue(syncResult is com.niqob.app.core.common.Result.Error)
        assertEquals("Cannot sync: child device is not paired yet.", viewModel.uiState.value.syncErrorMessage)
    }

    @Test
    fun syncTodayUsage_whenPaired_successfullySyncsTodaySummary() = runBlocking {
        val fakeUsageRepo = com.niqob.app.fakes.FakeScreenTimeUsageRepository()
        val customViewModel = UsageAccessViewModel(
            usageStatsRepository = fakeRepository,
            screenTimeUsageRepository = fakeUsageRepo
        )

        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-03",
            totalScreenTimeMillis = 2_700_000L, // 45 min
            appUsages = emptyList()
        )
        customViewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        // Simulate paired device ID
        val stateProperty = customViewModel.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateProperty.get(customViewModel) as kotlinx.coroutines.flow.MutableStateFlow<com.niqob.app.feature.child.usage.UsageAccessUiState>
        stateFlow.value = stateFlow.value.copy(childDeviceId = "device_test_123")

        val syncResult = customViewModel.syncTodayUsageSuspend()
        assertTrue(syncResult is com.niqob.app.core.common.Result.Success)

        val synced = (syncResult as com.niqob.app.core.common.Result.Success).data
        assertEquals("device_test_123", synced.deviceId)
        assertEquals(45, synced.totalUsageMinutes)
        assertEquals(75, synced.remainingMinutes)
        assertEquals(38, synced.usagePercentage)
        assertFalse(synced.isLimitReached)
        assertEquals(synced, customViewModel.uiState.value.lastSyncedUsage)
        assertNull(customViewModel.uiState.value.syncErrorMessage)
        assertEquals(1, fakeUsageRepo.upsertCount)
    }

    @Test
    fun checkPermissionAndRefresh_updatesEnforcementStateAndRepository() = runBlocking {
        val fakeEnforcementRepo = com.niqob.app.fakes.FakeScreenTimeEnforcementRepository()
        val customViewModel = UsageAccessViewModel(
            usageStatsRepository = fakeRepository,
            enforcementRepository = fakeEnforcementRepo
        )

        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-04",
            totalScreenTimeMillis = 3_600_000L, // 60 min
            appUsages = emptyList()
        )

        customViewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val state = customViewModel.uiState.value
        val enforcement = state.enforcementState
        assertNotNull(enforcement)
        assertEquals(60, enforcement!!.usedMinutes)
        assertEquals(120, enforcement.dailyLimitMinutes)
        assertEquals(60, enforcement.remainingMinutes)
        assertEquals(50, enforcement.usagePercentage)
        assertFalse(enforcement.isLimitReached)
        assertFalse(enforcement.isEnforcementActive)
        assertEquals(com.niqob.app.domain.model.ScreenTimeEnforcementStatus.WITHIN_LIMIT, enforcement.status)

        // Verify repository was updated
        assertEquals(enforcement, fakeEnforcementRepo.getEnforcementState().value)
        assertEquals(1, fakeEnforcementRepo.updateCount)
    }

    @Test
    fun setSimulatedLimit_updatesEnforcementStateToLimitReached() = runBlocking {
        val fakeEnforcementRepo = com.niqob.app.fakes.FakeScreenTimeEnforcementRepository()
        val customViewModel = UsageAccessViewModel(
            usageStatsRepository = fakeRepository,
            enforcementRepository = fakeEnforcementRepo
        )

        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-04",
            totalScreenTimeMillis = 3_600_000L, // 60 min
            appUsages = emptyList()
        )
        customViewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        // Override to 30 min limit -> limit reached
        customViewModel.setSimulatedLimit(30, true)

        val enforcement = customViewModel.uiState.value.enforcementState
        assertNotNull(enforcement)
        assertEquals(60, enforcement!!.usedMinutes)
        assertEquals(30, enforcement.dailyLimitMinutes)
        assertEquals(0, enforcement.remainingMinutes)
        assertEquals(200, enforcement.usagePercentage)
        assertTrue(enforcement.isLimitReached)
        assertTrue(enforcement.isEnforcementActive)
        assertEquals(com.niqob.app.domain.model.ScreenTimeEnforcementStatus.LIMIT_REACHED, enforcement.status)

        assertEquals(enforcement, fakeEnforcementRepo.getEnforcementState().value)
        assertEquals(2, fakeEnforcementRepo.updateCount)
    }

    @Test
    fun permissionRevoked_clearsEnforcementRepositoryAndState() = runBlocking {
        val fakeEnforcementRepo = com.niqob.app.fakes.FakeScreenTimeEnforcementRepository()
        val customViewModel = UsageAccessViewModel(
            usageStatsRepository = fakeRepository,
            enforcementRepository = fakeEnforcementRepo
        )

        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-04",
            totalScreenTimeMillis = 3_600_000L,
            appUsages = emptyList()
        )
        customViewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)
        assertNotNull(customViewModel.uiState.value.enforcementState)
        assertNotNull(fakeEnforcementRepo.getEnforcementState().value)

        // Revoke
        fakeRepository.permissionGranted = false
        customViewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        assertNull(customViewModel.uiState.value.enforcementState)
        assertNull(fakeEnforcementRepo.getEnforcementState().value)
    }

    @Test
    fun appPolicyDecisions_whenWithinLimit_allowsAllSamplePackages() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-04",
            totalScreenTimeMillis = 1_800_000L, // 30 min out of 120 min
            appUsages = emptyList()
        )
        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val decisions = viewModel.uiState.value.appPolicyDecisions
        assertEquals(4, decisions.size)
        assertTrue(decisions.all { it.isAllowed })
    }

    @Test
    fun appPolicyDecisions_whenLimitReached_allowsNiqobAndBlocksOthersUnlessExplicitlyAllowed() = runBlocking {
        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-04",
            totalScreenTimeMillis = 3_600_000L, // 60 min
            appUsages = emptyList()
        )
        viewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)
        viewModel.setSimulatedLimit(minutes = 0, enabled = true)

        val decisions = viewModel.uiState.value.appPolicyDecisions
        val niqobDecision = decisions.first { it.packageName == "com.niqob.app" }
        val settingsDecision = decisions.first { it.packageName == "com.android.settings" }

        assertTrue(niqobDecision.isAllowed)
        assertTrue(settingsDecision.isBlocked)

        // Now toggle allow for com.android.settings
        viewModel.toggleAllowPackageInPolicy("com.android.settings")
        val updatedDecisions = viewModel.uiState.value.appPolicyDecisions
        val updatedSettingsDecision = updatedDecisions.first { it.packageName == "com.android.settings" }

        assertTrue(updatedSettingsDecision.isAllowed)
    }

    @Test
    fun foregroundAppDetection_evaluatesDetectedAppAgainstEnforcementState() = runBlocking {
        val fakeDetector = FakeForegroundAppDetector(
            currentApp = ForegroundAppInfo("com.android.chrome", 1700000000000L),
            isPermissionGranted = true
        )
        val evaluateUseCase = EvaluateForegroundAppUseCase(
            foregroundAppDetector = fakeDetector,
            policyRepository = com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository.getInstance(),
            enforcementRepository = com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository.getInstance()
        )
        val customViewModel = UsageAccessViewModel(
            usageStatsRepository = fakeRepository,
            evaluateForegroundAppUseCase = evaluateUseCase
        )

        fakeRepository.permissionGranted = true
        fakeRepository.mockUsageSummary = DailyUsageSummary(
            date = "2026-10-04",
            totalScreenTimeMillis = 1_800_000L, // 30m out of 120m
            appUsages = emptyList()
        )
        customViewModel.checkPermissionAndRefreshSuspend(excludeSelf = true)

        val withinLimitState = customViewModel.uiState.value
        assertEquals("com.android.chrome", withinLimitState.detectedForegroundApp?.packageName)
        assertEquals(AppEnforcementAction.ALLOW, withinLimitState.foregroundAppDecision?.decision)

        // Now trigger limit reached (0m limit)
        customViewModel.setSimulatedLimit(minutes = 0, enabled = true)
        val limitReachedState = customViewModel.uiState.value
        assertEquals(AppEnforcementAction.BLOCK, limitReachedState.foregroundAppDecision?.decision)
        assertTrue(limitReachedState.foregroundAppDecision?.isBlocked ?: false)
    }

    @Test
    fun enforcementController_andDeviceOwner_wiringInViewModel() = runBlocking {
        val fakeController = com.niqob.app.fakes.FakeAppEnforcementController(
            policyRepository = com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository.getInstance(),
            enforcementRepository = com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository.getInstance(),
            isDeviceOwnerSimulated = false
        )
        val fakeDeviceOwner = com.niqob.app.fakes.FakeDeviceOwnerEnforcementManager(isDeviceOwner = false)

        val vm = UsageAccessViewModel(
            usageStatsRepository = fakeRepository,
            appEnforcementController = fakeController,
            deviceOwnerManager = fakeDeviceOwner
        )

        assertFalse(vm.uiState.value.isDeviceOwnerActive)
        assertFalse(vm.uiState.value.isEnforcementControllerRunning)

        vm.startEnforcementController()
        fakeController.start()
        assertTrue(fakeController.isRunning.value)

        val result = fakeController.handleForegroundPackage("com.android.chrome")
        assertNotNull(result)
        assertEquals("com.android.chrome", result.packageName)
        assertFalse(result.isRealBlockExecuted)

        vm.stopEnforcementController()
        fakeController.stop()
        assertFalse(fakeController.isRunning.value)
    }
}



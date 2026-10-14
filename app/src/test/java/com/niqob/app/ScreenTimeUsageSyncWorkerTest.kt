package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.NoOpDeviceSecretCipher
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.domain.model.AppUsageInfo
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.domain.model.ScreenTimeLimit
import com.niqob.app.domain.model.UserMode
import com.niqob.app.fakes.FakePairingRepository
import com.niqob.app.fakes.FakeScreenTimeRepository
import com.niqob.app.fakes.FakeScreenTimeUsageRepository
import com.niqob.app.fakes.FakeUsageStatsRepository
import com.niqob.app.feature.child.usage.sync.ScreenTimeSyncScheduler
import com.niqob.app.feature.child.usage.sync.ScreenTimeUsageSyncUseCase
import com.niqob.app.feature.child.usage.sync.SyncExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenTimeUsageSyncWorkerTest {

    private lateinit var fakeUsageStatsRepo: FakeUsageStatsRepository
    private lateinit var fakeScreenTimeRepo: FakeScreenTimeRepository
    private lateinit var fakePairingRepo: FakePairingRepository
    private lateinit var fakeScreenTimeUsageRepo: FakeScreenTimeUsageRepository
    private lateinit var syncUseCase: ScreenTimeUsageSyncUseCase

    private val testDeviceId = "child_dev_test_123"
    private val testDate = "2026-10-03"

    @Before
    fun setUp() {
        fakeUsageStatsRepo = FakeUsageStatsRepository(permissionGranted = true)
        fakeScreenTimeRepo = FakeScreenTimeRepository()
        fakePairingRepo = FakePairingRepository()
        fakeScreenTimeUsageRepo = FakeScreenTimeUsageRepository()

        syncUseCase = ScreenTimeUsageSyncUseCase(
            usageStatsRepo = fakeUsageStatsRepo,
            screenTimeRepo = fakeScreenTimeRepo,
            pairingRepo = fakePairingRepo,
            screenTimeUsageRepo = fakeScreenTimeUsageRepo,
            maxRetries = 3
        )
    }

    private fun setPairedChildSession(deviceId: String = testDeviceId) {
        val session = PairingSession(
            pairingId = "pair_sess_1",
            parentDeviceId = "parent_dev_1",
            childDeviceId = deviceId,
            pairingCode = "123456",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600_000L,
            status = PairingStatus.CONNECTED,
            familyId = "fam_1"
        )
        fakePairingRepo.setSessionDirectly(session)
        fakePairingRepo.setRoleDirectly(UserMode.CHILD)
        fakePairingRepo.fakeChildDeviceId = deviceId
        fakePairingRepo.fakeChildDeviceSecret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }

    @Test
    fun executeSync_whenChildNotPaired_skipsSyncGracefully() = runBlocking {
        fakePairingRepo.setCurrentRole(UserMode.CHILD)
        fakePairingRepo.fakeChildDeviceId = testDeviceId
        fakePairingRepo.fakeChildDeviceSecret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        fakePairingRepo.setSessionDirectly(null)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("not_paired", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenChildPairingPending_skipsSyncGracefully() = runBlocking {
        fakePairingRepo.setCurrentRole(UserMode.CHILD)
        fakePairingRepo.fakeChildDeviceId = testDeviceId
        fakePairingRepo.fakeChildDeviceSecret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val pendingSession = PairingSession(
            pairingId = "pair_sess_pending",
            parentDeviceId = "parent_dev_1",
            childDeviceId = null,
            pairingCode = "123456",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600_000L,
            status = PairingStatus.PENDING,
            familyId = "fam_1"
        )
        fakePairingRepo.setSessionDirectly(pendingSession)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("not_paired", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenParentMode_skipsWithoutQueryingUsageOrUpserting() = runBlocking {
        setPairedChildSession()
        fakePairingRepo.setCurrentRole(UserMode.PARENT)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("parent_mode", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeUsageStatsRepo.getTodayUsageCallCount)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenRoleNotChild_skipsWithoutQueryingUsageOrUpserting() = runBlocking {
        setPairedChildSession()
        fakePairingRepo.setCurrentRole(UserMode.UNSELECTED)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("role_not_child", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeUsageStatsRepo.getTodayUsageCallCount)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenChildSecretMissingOrBlank_skipsWithoutQueryingUsageOrUpserting() = runBlocking {
        setPairedChildSession()
        fakePairingRepo.fakeChildDeviceSecret = null

        val outcomeNull = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcomeNull is SyncExecutionResult.Skipped)
        assertEquals("missing_child_credentials", (outcomeNull as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeUsageStatsRepo.getTodayUsageCallCount)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)

        // Blank secret
        fakePairingRepo.fakeChildDeviceSecret = "   "
        val outcomeBlank = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcomeBlank is SyncExecutionResult.Skipped)
        assertEquals("missing_child_credentials", (outcomeBlank as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeUsageStatsRepo.getTodayUsageCallCount)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenChildDeviceIdMissing_skipsWithoutQueryingUsageOrUpserting() = runBlocking {
        setPairedChildSession()
        fakePairingRepo.fakeChildDeviceId = null
        val inMemoryCredStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )
        val useCaseWithStore = ScreenTimeUsageSyncUseCase(
            usageStatsRepo = fakeUsageStatsRepo,
            screenTimeRepo = fakeScreenTimeRepo,
            pairingRepo = fakePairingRepo,
            screenTimeUsageRepo = fakeScreenTimeUsageRepo,
            maxRetries = 3,
            credentialStore = inMemoryCredStore
        )

        val outcome = useCaseWithStore.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("missing_child_credentials", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeUsageStatsRepo.getTodayUsageCallCount)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenDeviceIdMismatch_skipsWithoutQueryingUsageOrUpserting() = runBlocking {
        setPairedChildSession(deviceId = "child_dev_session_1")
        fakePairingRepo.fakeChildDeviceId = "child_dev_stored_2"

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("device_id_mismatch", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeUsageStatsRepo.getTodayUsageCallCount)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenPermissionMissing_skipsSyncGracefully() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.permissionGranted = false

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Skipped)
        assertEquals("permission_missing", (outcome as SyncExecutionResult.Skipped).reason)
        assertEquals(0, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenPairedAndPermitted_calculatesAndUpsertsSuccessfully() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 3_600_000L, // 60 min
            appUsages = listOf(
                AppUsageInfo("com.google.android.youtube", 2_400_000L, testDate, lastTimeUsed = 0L, appLabel = "YouTube"),
                AppUsageInfo("com.android.settings", 1_200_000L, testDate, lastTimeUsed = 0L, appLabel = "Settings")
            )
        )
        // Configure server limit of 120 minutes
        fakeScreenTimeRepo.setScreenTimeLimit(testDeviceId, dailyLimitMinutes = 120, enabled = true)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Success)
        val success = outcome as SyncExecutionResult.Success
        assertEquals(60, success.usedMinutes)
        assertEquals(60, success.remainingMinutes)
        assertEquals(50, success.percentage)
        assertFalse(success.isLimitReached)

        // Verify repository was called exactly once with aggregate summary
        assertEquals(1, fakeScreenTimeUsageRepo.upsertCount)
        val stored = fakeScreenTimeUsageRepo.getDailyUsage(testDeviceId, testDate)
        assertTrue(stored is Result.Success)
        val record = (stored as Result.Success).data
        assertNotNull(record)
        assertEquals(testDeviceId, record?.deviceId)
        assertEquals(testDate, record?.usageDate)
        assertEquals(60, record?.totalUsageMinutes)
        assertEquals(120, record?.dailyLimitMinutes)
        assertEquals(60, record?.remainingMinutes)
        assertEquals(50, record?.usagePercentage)
        assertFalse(record?.isLimitReached ?: true)
    }

    @Test
    fun executeSync_whenZeroUsage_upsertsZeroUsageSuccessfully() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 0L,
            appUsages = emptyList()
        )
        fakeScreenTimeRepo.setScreenTimeLimit(testDeviceId, dailyLimitMinutes = 60, enabled = true)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Success)
        val success = outcome as SyncExecutionResult.Success
        assertEquals(0, success.usedMinutes)
        assertEquals(60, success.remainingMinutes)
        assertEquals(0, success.percentage)
        assertFalse(success.isLimitReached)
        assertEquals(1, fakeScreenTimeUsageRepo.upsertCount)
    }

    @Test
    fun executeSync_whenUsageExceedsLimit_upsertsLimitReachedTrue() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 5_400_000L, // 90 min
            appUsages = listOf(
                AppUsageInfo("com.android.chrome", 5_400_000L, testDate, lastTimeUsed = 0L, appLabel = "Chrome")
            )
        )
        // 30 min limit
        fakeScreenTimeRepo.setScreenTimeLimit(testDeviceId, dailyLimitMinutes = 30, enabled = true)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Success)
        val success = outcome as SyncExecutionResult.Success
        assertEquals(90, success.usedMinutes)
        assertEquals(0, success.remainingMinutes)
        assertEquals(300, success.percentage) // 90 / 30 * 100 = 300%
        assertTrue(success.isLimitReached)
    }

    @Test
    fun executeSync_whenLimitDisabled_limitIsNotReachedEvenIfUsageHigh() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 7_200_000L, // 120 min
            appUsages = emptyList()
        )
        fakeScreenTimeRepo.setScreenTimeLimit(testDeviceId, dailyLimitMinutes = 60, enabled = false)

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Success)
        val success = outcome as SyncExecutionResult.Success
        assertEquals(120, success.usedMinutes)
        assertFalse(success.isLimitReached) // Disabled limit
    }

    @Test
    fun executeSync_whenScreenTimeRepoFails_usesFallbackLimitAndSucceeds() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 1_800_000L, // 30 min
            appUsages = emptyList()
        )
        fakeScreenTimeRepo.shouldFail = true // Simulated network error fetching limit

        val outcome = syncUseCase.executeSync(attemptCount = 0)

        assertTrue(outcome is SyncExecutionResult.Success)
        val success = outcome as SyncExecutionResult.Success
        assertEquals(30, success.usedMinutes)
        // Fallback is 120 minutes -> remaining = 90
        assertEquals(90, success.remainingMinutes)
    }

    @Test
    fun executeSync_whenUsageQueryFails_returnsRetryOnEarlyAttempt() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.shouldFail = true

        val outcome = syncUseCase.executeSync(attemptCount = 1) // attempt 1 < maxRetries (3)

        assertTrue(outcome is SyncExecutionResult.Retry)
    }

    @Test
    fun executeSync_whenUsageQueryFails_returnsFailureOnMaxAttempts() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.shouldFail = true

        val outcome = syncUseCase.executeSync(attemptCount = 3) // attempt 3 == maxRetries

        assertTrue(outcome is SyncExecutionResult.Failure)
    }

    @Test
    fun executeSync_whenCloudUpsertFails_returnsRetryOnEarlyAttempt() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 1_800_000L,
            appUsages = emptyList()
        )
        fakeScreenTimeUsageRepo.shouldFail = true
        fakeScreenTimeUsageRepo.failureException = Exception("Temporary 503 Supabase Service Unavailable")

        val outcome = syncUseCase.executeSync(attemptCount = 1)

        assertTrue(outcome is SyncExecutionResult.Retry)
        assertEquals("Temporary 503 Supabase Service Unavailable", (outcome as SyncExecutionResult.Retry).message)
    }

    @Test
    fun executeSync_whenCloudUpsertFails_returnsFailureOnMaxAttempts() = runBlocking {
        setPairedChildSession()
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 1_800_000L,
            appUsages = emptyList()
        )
        fakeScreenTimeUsageRepo.shouldFail = true

        val outcome = syncUseCase.executeSync(attemptCount = 3)

        assertTrue(outcome is SyncExecutionResult.Failure)
    }

    @Test
    fun ScreenTimeSyncScheduler_constantsAndPolicies_areWellConfigured() {
        assertEquals("niqob_screen_time_sync", ScreenTimeSyncScheduler.TAG_SCREEN_TIME_SYNC)
        assertEquals("niqob_screen_time_usage_periodic_sync", ScreenTimeSyncScheduler.UNIQUE_PERIODIC_WORK_NAME)
        assertEquals("niqob_screen_time_usage_one_time_sync", ScreenTimeSyncScheduler.UNIQUE_ONE_TIME_WORK_NAME)
        assertEquals("SUCCESS", ScreenTimeSyncScheduler.SYNC_STATUS_SUCCESS)
        assertEquals("FAILED", ScreenTimeSyncScheduler.SYNC_STATUS_FAILED)
        assertEquals("SKIPPED_NOT_PAIRED", ScreenTimeSyncScheduler.SYNC_STATUS_SKIPPED_NOT_PAIRED)
        assertEquals("SKIPPED_PERMISSION", ScreenTimeSyncScheduler.SYNC_STATUS_SKIPPED_PERMISSION)
        assertEquals("SKIPPED_ROLE", ScreenTimeSyncScheduler.SYNC_STATUS_SKIPPED_ROLE)
    }

    @Test
    fun isEnrolledChildDevice_evaluatesCorrectlyAcrossRolesAndCredentials() {
        val inMemoryCredStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )
        fakePairingRepo.setRoleDirectly(UserMode.PARENT)
        assertFalse(ScreenTimeSyncScheduler.isEnrolledChildDevice(context = null, pairingRepository = fakePairingRepo, credentialStore = inMemoryCredStore))

        // Child role, but no credentials in store
        fakePairingRepo.setRoleDirectly(UserMode.CHILD)
        assertFalse(ScreenTimeSyncScheduler.isEnrolledChildDevice(context = null, pairingRepository = fakePairingRepo, credentialStore = inMemoryCredStore))

        // Credentials saved and matching session
        inMemoryCredStore.save(testDeviceId, "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
        setPairedChildSession(deviceId = testDeviceId)
        assertTrue(ScreenTimeSyncScheduler.isEnrolledChildDevice(context = null, pairingRepository = fakePairingRepo, credentialStore = inMemoryCredStore))

        // Device ID mismatch between credential store and session
        setPairedChildSession(deviceId = "different_device_id")
        assertFalse(ScreenTimeSyncScheduler.isEnrolledChildDevice(context = null, pairingRepository = fakePairingRepo, credentialStore = inMemoryCredStore))

        // Session not connected (e.g. revoked)
        val revokedSession = PairingSession(
            pairingId = "pair_sess_1",
            parentDeviceId = "parent_dev_1",
            childDeviceId = testDeviceId,
            pairingCode = "123456",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600_000L,
            status = PairingStatus.REVOKED,
            familyId = "fam_1"
        )
        fakePairingRepo.setSessionDirectly(revokedSession)
        assertFalse(ScreenTimeSyncScheduler.isEnrolledChildDevice(context = null, pairingRepository = fakePairingRepo, credentialStore = inMemoryCredStore))
    }

    @Test
    fun reconcileScheduling_whenParentMode_doesNotSchedulePeriodicSync_includingAfterAppRestart() {
        val inMemoryCredStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )

        // 1. Initial parent mode selection
        fakePairingRepo.setRoleDirectly(UserMode.PARENT)
        assertFalse(ScreenTimeSyncScheduler.isEnrolledChildDevice(context = null, pairingRepository = fakePairingRepo, credentialStore = inMemoryCredStore))

        // 2. Simulate app restart: persisted state is restored as PARENT role and empty child credentials
        val restartedPairingRepo = FakePairingRepository().apply {
            setRoleDirectly(UserMode.PARENT)
            // Parent has pairing session from remote query, but no local child credentials
            setSessionDirectly(
                PairingSession(
                    pairingId = "pair_sess_parent_view",
                    parentDeviceId = "parent_dev_1",
                    childDeviceId = "child_dev_123",
                    pairingCode = "123456",
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 600_000L,
                    status = PairingStatus.CONNECTED,
                    familyId = "fam_1"
                )
            )
        }
        val restartedCredStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        ) // Empty credentials on parent device

        // Verification: isEnrolledChildDevice MUST return false on app restart for parent device
        assertFalse(
            "Parent device must NOT be considered an enrolled child device on app restart",
            ScreenTimeSyncScheduler.isEnrolledChildDevice(
                context = null,
                pairingRepository = restartedPairingRepo,
                credentialStore = restartedCredStore
            )
        )
    }

    @Test
    fun repeatedSync_updatesSameRowInFakeRepoWithoutDuplicates() = runBlocking {
        setPairedChildSession()

        // 1. First sync: 30 minutes
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 1_800_000L,
            appUsages = emptyList()
        )
        val outcome1 = syncUseCase.executeSync(0)
        assertTrue(outcome1 is SyncExecutionResult.Success)

        // 2. Second sync: 60 minutes on same day
        fakeUsageStatsRepo.mockUsageSummary = DailyUsageSummary(
            date = testDate,
            totalScreenTimeMillis = 3_600_000L,
            appUsages = emptyList()
        )
        val outcome2 = syncUseCase.executeSync(0)
        assertTrue(outcome2 is SyncExecutionResult.Success)

        // Verify only 1 row exists in the storage for this device and date
        val row = fakeScreenTimeUsageRepo.getDailyUsage(testDeviceId, testDate)
        assertTrue(row is Result.Success)
        assertEquals(60, (row as Result.Success).data?.totalUsageMinutes)
        assertEquals(2, fakeScreenTimeUsageRepo.upsertCount)
    }
}

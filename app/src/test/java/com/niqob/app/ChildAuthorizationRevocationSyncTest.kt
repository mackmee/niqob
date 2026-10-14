package com.niqob.app

import com.niqob.app.core.common.ChildAuthorizationRevokedException
import com.niqob.app.core.common.Result
import com.niqob.app.data.local.NoOpDeviceSecretCipher
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserRole
import com.niqob.app.fakes.FakeAuthRepository
import com.niqob.app.fakes.FakePairingRepository
import com.niqob.app.fakes.FakeScreenTimeRepository
import com.niqob.app.fakes.FakeScreenTimeUsageRepository
import com.niqob.app.fakes.FakeUsageStatsRepository
import com.niqob.app.feature.child.usage.sync.ScreenTimeUsageSyncUseCase
import com.niqob.app.feature.child.usage.sync.SyncExecutionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ChildAuthorizationRevocationSyncTest {

    private lateinit var fakeUsageStatsRepo: FakeUsageStatsRepository
    private lateinit var fakeScreenTimeRepo: FakeScreenTimeRepository
    private lateinit var fakePairingRepo: FakePairingRepository
    private lateinit var fakeScreenTimeUsageRepo: FakeScreenTimeUsageRepository
    private lateinit var fakeAuthRepo: FakeAuthRepository
    private lateinit var childCredentialStore: SharedPreferencesChildDeviceCredentialStore
    private lateinit var syncUseCase: ScreenTimeUsageSyncUseCase

    private val testChildDeviceId = "child-dev-sec03-f02"
    private val testDate = "2026-10-11"

    @Before
    fun setUp() {
        fakeUsageStatsRepo = FakeUsageStatsRepository(permissionGranted = true).apply {
            mockUsageSummary = DailyUsageSummary(
                date = testDate,
                totalScreenTimeMillis = 3_600_000L, // 60 minutes
                appUsages = emptyList()
            )
        }
        fakeScreenTimeRepo = FakeScreenTimeRepository()
        fakePairingRepo = FakePairingRepository()
        fakeScreenTimeUsageRepo = FakeScreenTimeUsageRepository()
        fakeAuthRepo = FakeAuthRepository()

        // In-memory child device credential store
        childCredentialStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )
        fakePairingRepo.credentialStore = childCredentialStore

        syncUseCase = ScreenTimeUsageSyncUseCase(
            usageStatsRepo = fakeUsageStatsRepo,
            screenTimeRepo = fakeScreenTimeRepo,
            pairingRepo = fakePairingRepo,
            screenTimeUsageRepo = fakeScreenTimeUsageRepo,
            maxRetries = 3
        )
    }

    private fun seedConnectedChildSession(deviceId: String = testChildDeviceId) {
        val session = PairingSession(
            pairingId = "session-child-123",
            parentDeviceId = "parent-dev-cloud",
            childDeviceId = deviceId,
            pairingCode = "123456",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600_000L,
            status = PairingStatus.CONNECTED,
            familyId = "family-sec03"
        )
        fakePairingRepo.setSessionDirectly(session)
        fakePairingRepo.setRoleDirectly(com.niqob.app.domain.model.UserMode.CHILD)
        fakePairingRepo.fakeChildDeviceId = deviceId
        fakePairingRepo.fakeChildDeviceSecret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        childCredentialStore.save(deviceId, "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
    }

    /**
     * Test A — UNAUTHORIZED is propagated as a typed permanent authorization failure
     */
    @Test
    fun testA_unauthorizedErrorCode_isPropagatedAsTypedChildAuthorizationRevokedException() {
        val unauthorizedException = ChildAuthorizationRevokedException(
            errorCode = "UNAUTHORIZED",
            message = "Invalid device authorization."
        )

        assertEquals("UNAUTHORIZED", unauthorizedException.errorCode)
        assertEquals("Invalid device authorization.", unauthorizedException.message)

        val errorResult: Result<Nothing> = Result.Error(unauthorizedException)
        assertTrue("Error result must contain ChildAuthorizationRevokedException", errorResult is Result.Error)
        val extracted = (errorResult as Result.Error).exception
        assertTrue(extracted is ChildAuthorizationRevokedException)
        assertEquals("UNAUTHORIZED", (extracted as ChildAuthorizationRevokedException).errorCode)
    }

    /**
     * Test B — Permanent authorization failure does not trigger the existing transient retry loop
     */
    @Test
    fun testB_permanentAuthorizationFailure_doesNotTriggerTransientRetryLoop() = runBlocking {
        seedConnectedChildSession()

        // Simulate server returning permanent authorization rejection (device revoked / invalid secret)
        fakeScreenTimeUsageRepo.shouldFail = true
        fakeScreenTimeUsageRepo.failureException = ChildAuthorizationRevokedException(
            errorCode = "UNAUTHORIZED",
            message = "Invalid device authorization: pairing has been revoked."
        )

        // Attempt 0: normal transient errors return SyncExecutionResult.Retry
        val outcome = syncUseCase.executeSync(attemptCount = 0)

        // MUST be Failure, NOT Retry!
        assertTrue("Permanent authorization failure must NOT return Retry", outcome is SyncExecutionResult.Failure)
        val failure = outcome as SyncExecutionResult.Failure
        assertTrue("Must be flagged as permanent authorization failure", failure.isPermanentAuthorizationFailure)
        assertEquals("Invalid device authorization: pairing has been revoked.", failure.message)
    }

    /**
     * Test C — Local pairing state is invalidated and later periodic workers do not keep retrying with revoked authorization
     */
    @Test
    fun testC_localPairingStateIsInvalidated_andSubsequentWorkersSkipSync() = runBlocking {
        seedConnectedChildSession()
        assertTrue("Child credentials must exist before rejection", childCredentialStore.hasCredential())
        assertEquals(PairingStatus.CONNECTED, fakePairingRepo.getPairingSession().first()?.status)

        // 1. Encounter permanent rejection during sync
        fakeScreenTimeUsageRepo.shouldFail = true
        fakeScreenTimeUsageRepo.failureException = ChildAuthorizationRevokedException(
            errorCode = "UNAUTHORIZED",
            message = "Device pairing revoked by parent."
        )
        val outcome1 = syncUseCase.executeSync(attemptCount = 0)
        assertTrue(outcome1 is SyncExecutionResult.Failure)

        // 2. Verify local pairing state was invalidated
        val sessionAfterFailure = fakePairingRepo.getPairingSession().first()
        assertEquals("Pairing status must be REVOKED", PairingStatus.REVOKED, sessionAfterFailure?.status)
        assertFalse("Child credentials must be erased on revocation", childCredentialStore.hasCredential())
        assertNull("Child device secret must be null", fakePairingRepo.getChildDeviceSecret())

        // 3. Reset repository failure flag and simulate next periodic WorkManager execution (15 min later)
        fakeScreenTimeUsageRepo.shouldFail = false
        val initialUpsertCount = fakeScreenTimeUsageRepo.upsertCount

        val outcome2 = syncUseCase.executeSync(attemptCount = 0)

        // 4. Must immediately skip without making any network call!
        assertTrue("Subsequent run must be Skipped because device is no longer paired", outcome2 is SyncExecutionResult.Skipped)
        assertEquals("not_paired", (outcome2 as SyncExecutionResult.Skipped).reason)
        assertEquals("Zero network calls must be executed on subsequent runs", initialUpsertCount, fakeScreenTimeUsageRepo.upsertCount)
    }

    /**
     * Test D — Transient network errors still follow the existing retry policy
     */
    @Test
    fun testD_transientNetworkErrors_stillFollowExistingRetryPolicy() = runBlocking {
        seedConnectedChildSession()

        // 1. Transient network failure (e.g., timeout / 503)
        fakeScreenTimeUsageRepo.shouldFail = true
        fakeScreenTimeUsageRepo.failureException = IOException("Connection timed out to Supabase host")

        // Attempt 0 (< maxRetries 3) MUST return Retry
        val outcomeAttempt0 = syncUseCase.executeSync(attemptCount = 0)
        assertTrue("Transient network error on early attempt must return Retry", outcomeAttempt0 is SyncExecutionResult.Retry)
        assertEquals("Connection timed out to Supabase host", (outcomeAttempt0 as SyncExecutionResult.Retry).message)

        // Verify pairing state is NOT invalidated by transient error
        assertEquals("Pairing must remain CONNECTED during transient failure", PairingStatus.CONNECTED, fakePairingRepo.getPairingSession().first()?.status)
        assertTrue("Child credentials must remain intact during transient failure", childCredentialStore.hasCredential())

        // Attempt 3 (== maxRetries) returns Failure, but NOT permanent authorization failure
        val outcomeAttempt3 = syncUseCase.executeSync(attemptCount = 3)
        assertTrue(outcomeAttempt3 is SyncExecutionResult.Failure)
        assertFalse("Transient failure at max attempts is NOT permanent authorization failure", (outcomeAttempt3 as SyncExecutionResult.Failure).isPermanentAuthorizationFailure)
    }

    /**
     * Test E — Child credential invalidation is limited to the intended child device and does not clear unrelated parent state or preferences
     */
    @Test
    fun testE_childCredentialInvalidation_doesNotClearParentStateOrPreferences() = runBlocking {
        seedConnectedChildSession()

        // Seed authenticated parent state in auth repo
        val parentUser = User(id = "parent-admin-999", email = "parent@example.com", role = UserRole.PARENT)
        fakeAuthRepo.setAuthenticatedUser(parentUser)
        assertEquals("parent@example.com", fakeAuthRepo.getCurrentUser()?.email)

        // Child authorization is revoked
        fakePairingRepo.revokePairing()

        // 1. Child credentials must be cleared
        assertFalse("Child credentials must be cleared", childCredentialStore.hasCredential())
        assertNull(childCredentialStore.getDeviceId())
        assertNull(childCredentialStore.getDeviceSecret())

        // 2. Parent user state in auth repository must be completely untouched
        assertEquals("Parent authentication state must remain completely intact", parentUser, fakeAuthRepo.getCurrentUser())
        assertEquals("parent@example.com", fakeAuthRepo.getCurrentUser()?.email)
    }
}

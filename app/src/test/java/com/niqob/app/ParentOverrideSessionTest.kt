package com.niqob.app

import com.niqob.app.data.local.enforcement.DefaultParentOverrideSessionManager
import com.niqob.app.data.local.enforcement.InMemoryParentOverrideSessionStore
import com.niqob.app.data.local.enforcement.LocalParentOverrideAuthenticator
import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.AttemptCheckResult
import com.niqob.app.domain.enforcement.OverrideAttemptResult
import com.niqob.app.domain.enforcement.ParentOverrideAttemptTracker
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import com.niqob.app.fakes.FakeParentOverrideAuthenticator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentOverrideSessionTest {

    private lateinit var authenticator: FakeParentOverrideAuthenticator
    private lateinit var sessionStore: InMemoryParentOverrideSessionStore
    private lateinit var attemptTracker: ParentOverrideAttemptTracker
    private lateinit var enforcementRepo: LocalScreenTimeEnforcementRepository
    private lateinit var sessionManager: DefaultParentOverrideSessionManager

    private var fakeCurrentTime: Long = 1_000_000L

    @Before
    fun setUp() {
        fakeCurrentTime = 1_000_000L
        authenticator = FakeParentOverrideAuthenticator(validPin = "1234")
        sessionStore = InMemoryParentOverrideSessionStore(timeProvider = { fakeCurrentTime })
        attemptTracker = ParentOverrideAttemptTracker(timeProvider = { fakeCurrentTime })
        enforcementRepo = LocalScreenTimeEnforcementRepository(
            sessionStore = sessionStore,
            timeProvider = { fakeCurrentTime }
        )
        sessionManager = DefaultParentOverrideSessionManager(
            authenticator = authenticator,
            sessionStore = sessionStore,
            attemptTracker = attemptTracker,
            enforcementRepository = enforcementRepo,
            timeProvider = { fakeCurrentTime }
        )
    }

    @After
    fun tearDown() {
        enforcementRepo.clear()
        sessionStore.clear()
    }

    // A. New session starts correctly
    @Test
    fun testA_newSessionStartsCorrectly() {
        val session = ParentOverrideSession.create(
            durationMinutes = 15,
            activatedAtMillis = fakeCurrentTime
        )
        assertTrue(session.isActive)
        assertEquals(fakeCurrentTime, session.activatedAtMillis)
        assertEquals(fakeCurrentTime + 15 * 60 * 1000L, session.expiresAtMillis)
        assertEquals(15, session.durationMinutes)
        assertTrue(session.isCurrentlyActive(fakeCurrentTime))
    }

    // B. 15-minute session is active before expiration
    @Test
    fun testB_fifteenMinuteSessionActiveBeforeExpiration() {
        val session = ParentOverrideSession.create(
            durationMinutes = 15,
            activatedAtMillis = fakeCurrentTime
        )
        // Check halfway through (7.5 minutes)
        val halfway = fakeCurrentTime + (7 * 60 * 1000L + 30 * 1000L)
        assertFalse(session.isExpired(halfway))
        assertTrue(session.isCurrentlyActive(halfway))
        assertEquals(8, session.remainingMinutes(halfway))
    }

    // C. Session expires exactly at expiration timestamp
    @Test
    fun testC_sessionExpiresExactlyAtExpirationTimestamp() {
        val session = ParentOverrideSession.create(
            durationMinutes = 15,
            activatedAtMillis = fakeCurrentTime
        )
        val expirationTime = fakeCurrentTime + 15 * 60 * 1000L

        // 1 ms before expiration -> still active
        assertFalse(session.isExpired(expirationTime - 1L))
        assertTrue(session.isCurrentlyActive(expirationTime - 1L))

        // Exactly at expiration -> expired
        assertTrue(session.isExpired(expirationTime))
        assertFalse(session.isCurrentlyActive(expirationTime))
        assertEquals(0, session.remainingMinutes(expirationTime))
    }

    // D. Session remains expired after process restart
    @Test
    fun testD_sessionRemainsExpiredAfterProcessRestart() {
        val session = ParentOverrideSession.create(
            durationMinutes = 15,
            activatedAtMillis = fakeCurrentTime
        )
        sessionStore.save(session)

        // Advance time past expiration
        fakeCurrentTime += 16 * 60 * 1000L

        // Simulate app/repository restart with same persisted store
        val newRepo = LocalScreenTimeEnforcementRepository(
            sessionStore = sessionStore,
            timeProvider = { fakeCurrentTime }
        )
        assertNull(newRepo.getActiveOverrideSession())
    }

    // E. Session persists across repository recreation
    @Test
    fun testE_sessionPersistsAcrossRepositoryRecreation() {
        val session = ParentOverrideSession.create(
            durationMinutes = 15,
            activatedAtMillis = fakeCurrentTime
        )
        sessionStore.save(session)

        // Simulate repository recreation within active 15m window (at 5 minutes)
        fakeCurrentTime += 5 * 60 * 1000L
        val restoredRepo = LocalScreenTimeEnforcementRepository(
            sessionStore = sessionStore,
            timeProvider = { fakeCurrentTime }
        )
        val active = restoredRepo.getActiveOverrideSession()
        assertNotNull(active)
        assertTrue(active!!.isCurrentlyActive(fakeCurrentTime))
        assertEquals(10, active.remainingMinutes(fakeCurrentTime))
    }

    // F. Expired persisted session cannot disable enforcement
    @Test
    fun testF_expiredPersistedSessionCannotDisableEnforcement() {
        val expiredSession = ParentOverrideSession(
            isActive = true,
            activatedAtMillis = fakeCurrentTime - 20 * 60 * 1000L,
            expiresAtMillis = fakeCurrentTime - 5 * 60 * 1000L,
            durationMinutes = 15
        )
        sessionStore.save(expiredSession)

        val state = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 135,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 112,
            usageDate = "2026-10-05",
            overrideSession = expiredSession
        )
        enforcementRepo.updateEnforcementState(state)

        val currentState = enforcementRepo.getEnforcementState().value
        assertNotNull(currentState)
        assertFalse(currentState!!.isParentOverrideActive)
        assertTrue("Expired override must not disable enforcement", currentState.isEnforcementActive)
    }

    // G. 3 failed attempts trigger 60-second cooldown
    @Test
    fun testG_threeFailedAttemptsTriggerCooldown() = runBlocking {
        assertEquals(AttemptCheckResult.Allowed, attemptTracker.checkCanAttempt(fakeCurrentTime))

        // Fail 1
        sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)
        assertEquals(AttemptCheckResult.Allowed, attemptTracker.checkCanAttempt(fakeCurrentTime))

        // Fail 2
        sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)
        assertEquals(AttemptCheckResult.Allowed, attemptTracker.checkCanAttempt(fakeCurrentTime))

        // Fail 3 -> triggers 60s cooldown
        val result3 = sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)
        assertTrue(result3 is OverrideAttemptResult.CooldownActive)
        assertEquals(60, (result3 as OverrideAttemptResult.CooldownActive).remainingSeconds)

        val check = attemptTracker.checkCanAttempt(fakeCurrentTime)
        assertTrue(check is AttemptCheckResult.InCooldown)
    }

    // H. 5 failed attempts trigger 15-minute lockout
    @Test
    fun testH_fiveFailedAttemptsTriggerLockout() = runBlocking {
        // Fail 1, 2, 3
        repeat(3) { sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime) }

        // Advance 61 seconds to exit cooldown
        fakeCurrentTime += 61 * 1000L

        // Fail 4
        sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)

        // Fail 5 -> triggers 15m lockout
        val result5 = sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)
        assertTrue(result5 is OverrideAttemptResult.LockoutActive)
        assertEquals(15, (result5 as OverrideAttemptResult.LockoutActive).remainingMinutes)

        val check = attemptTracker.checkCanAttempt(fakeCurrentTime)
        assertTrue(check is AttemptCheckResult.InLockout)
    }

    // I. Successful authentication resets failed attempts
    @Test
    fun testI_successfulAuthResetsFailedAttempts() = runBlocking {
        // Fail twice
        sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)
        sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime)
        assertEquals(2, attemptTracker.getFailedAttempts())

        // Succeed on third attempt
        val success = sessionManager.attemptOverride("1234", currentTimeMillis = fakeCurrentTime)
        assertTrue(success is OverrideAttemptResult.Success)
        assertEquals(0, attemptTracker.getFailedAttempts())
        assertEquals(AttemptCheckResult.Allowed, attemptTracker.checkCanAttempt(fakeCurrentTime))
    }

    // J. Authentication during cooldown fails
    @Test
    fun testJ_authDuringCooldownFails() = runBlocking {
        repeat(3) { sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime) }

        // Attempt during cooldown with correct PIN -> must be rejected without checking PIN
        val attemptsBefore = authenticator.attempts
        val result = sessionManager.attemptOverride("1234", currentTimeMillis = fakeCurrentTime + 10_000L)
        assertTrue(result is OverrideAttemptResult.CooldownActive)
        assertEquals("Authenticator must not be called during cooldown", attemptsBefore, authenticator.attempts)
    }

    // K. Authentication during lockout fails
    @Test
    fun testK_authDuringLockoutFails() = runBlocking {
        repeat(3) { sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime) }
        fakeCurrentTime += 61 * 1000L
        repeat(2) { sessionManager.attemptOverride("0000", currentTimeMillis = fakeCurrentTime) }

        // Attempt during lockout with correct PIN -> must be rejected without checking PIN
        val attemptsBefore = authenticator.attempts
        val result = sessionManager.attemptOverride("1234", currentTimeMillis = fakeCurrentTime + 10_000L)
        assertTrue(result is OverrideAttemptResult.LockoutActive)
        assertEquals("Authenticator must not be called during lockout", attemptsBefore, authenticator.attempts)
    }

    // L. Active override allows normal app access
    @Test
    fun testL_activeOverrideAllowsNormalAppAccess() = runBlocking {
        val baseState = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 140,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 116,
            usageDate = "2026-10-05"
        )
        enforcementRepo.updateEnforcementState(baseState)

        // Start 15m override
        val result = sessionManager.attemptOverride("1234", durationMinutes = 15, currentTimeMillis = fakeCurrentTime)
        assertTrue(result is OverrideAttemptResult.Success)

        val updatedState = enforcementRepo.getEnforcementState().value
        assertNotNull(updatedState)
        assertTrue(updatedState!!.isParentOverrideActive)
        assertFalse(updatedState.isEnforcementActive(fakeCurrentTime))

        // Evaluate Chrome
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = updatedState,
            policy = AppEnforcementPolicy.DEFAULT,
            timestampMillis = fakeCurrentTime
        )
        assertEquals(AppEnforcementAction.ALLOW, decision.decision)
    }

    // M. Expired override blocks Chrome again
    @Test
    fun testM_expiredOverrideBlocksChromeAgain() = runBlocking {
        val baseState = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 140,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 116,
            usageDate = "2026-10-05"
        )
        enforcementRepo.updateEnforcementState(baseState)

        // Start 15m override
        sessionManager.attemptOverride("1234", durationMinutes = 15, currentTimeMillis = fakeCurrentTime)

        // Advance time past expiration
        fakeCurrentTime += 16 * 60 * 1000L

        val currentState = enforcementRepo.getEnforcementState().value
        assertNotNull(currentState)

        // AppEnforcementEvaluator checks authoritative expiration
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = currentState,
            policy = AppEnforcementPolicy.DEFAULT,
            timestampMillis = fakeCurrentTime
        )
        assertEquals(AppEnforcementAction.BLOCK, decision.decision)
    }

    // N. NIQOB remains accessible
    @Test
    fun testN_niqobRemainsAccessible() {
        val state = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 150,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 125,
            usageDate = "2026-10-05"
        )
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.niqob.app",
            enforcementState = state,
            policy = AppEnforcementPolicy.DEFAULT
        )
        assertEquals(AppEnforcementAction.ALLOW, decision.decision)
        assertEquals("NIQOB self application is always allowed", decision.reason)
    }

    // O. Supabase screen_time_limits is unchanged
    @Test
    fun testO_supabaseScreenTimeLimitsUnchanged() = runBlocking {
        val state = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 120,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 100,
            usageDate = "2026-10-05"
        )
        enforcementRepo.updateEnforcementState(state)

        sessionManager.attemptOverride("1234", currentTimeMillis = fakeCurrentTime)

        // Daily limit in repository remains exactly 120 minutes
        assertEquals(120, enforcementRepo.getEnforcementState().value?.dailyLimitMinutes)
    }

    // P. screen_time_usage_daily is unchanged
    @Test
    fun testP_screenTimeUsageDailyUnchanged() = runBlocking {
        val state = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 145,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 120,
            usageDate = "2026-10-05"
        )
        enforcementRepo.updateEnforcementState(state)

        sessionManager.attemptOverride("1234", currentTimeMillis = fakeCurrentTime)

        // Tracked usage remains 145 minutes
        assertEquals(145, enforcementRepo.getEnforcementState().value?.usedMinutes)
    }

    // Q. Supported durations
    @Test
    fun testQ_supportedDurations() {
        assertEquals(listOf(15, 30, 60), ParentOverrideSession.SUPPORTED_DURATIONS_MINUTES)
        val session30 = ParentOverrideSession.create(30, fakeCurrentTime)
        assertEquals(30, session30.durationMinutes)
        assertEquals(fakeCurrentTime + 30 * 60 * 1000L, session30.expiresAtMillis)

        val session60 = ParentOverrideSession.create(60, fakeCurrentTime)
        assertEquals(60, session60.durationMinutes)
        assertEquals(fakeCurrentTime + 60 * 60 * 1000L, session60.expiresAtMillis)
    }
}

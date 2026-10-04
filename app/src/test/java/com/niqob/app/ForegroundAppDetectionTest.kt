package com.niqob.app

import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ForegroundAppInfo
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import com.niqob.app.domain.usecase.EvaluateForegroundAppUseCase
import com.niqob.app.fakes.FakeForegroundAppDetector
import com.niqob.app.fakes.FakeScreenTimeEnforcementRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundAppDetectionTest {

    private lateinit var fakeDetector: FakeForegroundAppDetector
    private lateinit var policyRepository: LocalAppEnforcementPolicyRepository
    private lateinit var enforcementRepository: FakeScreenTimeEnforcementRepository
    private lateinit var useCase: EvaluateForegroundAppUseCase

    @Before
    fun setUp() {
        LocalAppEnforcementPolicyRepository.resetForTesting()
        fakeDetector = FakeForegroundAppDetector()
        policyRepository = LocalAppEnforcementPolicyRepository.getInstance()
        enforcementRepository = FakeScreenTimeEnforcementRepository()
        useCase = EvaluateForegroundAppUseCase(
            foregroundAppDetector = fakeDetector,
            policyRepository = policyRepository,
            enforcementRepository = enforcementRepository
        )
    }

    @After
    fun tearDown() {
        LocalAppEnforcementPolicyRepository.resetForTesting()
    }

    @Test
    fun detector_returns_current_package() = runBlocking {
        val testApp = ForegroundAppInfo("com.android.chrome", 1700000000000L)
        fakeDetector.currentApp = testApp

        val detected = fakeDetector.getCurrentForegroundApp()

        assertNotNull(detected)
        assertEquals("com.android.chrome", detected?.packageName)
        assertEquals(1700000000000L, detected?.timestampMillis)
    }

    @Test
    fun detector_returns_null_when_no_usage_event_exists() = runBlocking {
        fakeDetector.currentApp = null

        val result = useCase()

        assertNull(result.foregroundApp)
        assertNull(result.decision)
    }

    @Test
    fun blank_package_is_handled_safely() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo("   ", 1700000000000L)

        val result = useCase()

        assertNull(result.foregroundApp)
        assertNull(result.decision)
    }

    @Test
    fun detector_and_use_case_handle_usage_access_unavailable() = runBlocking {
        fakeDetector.isPermissionGranted = false
        fakeDetector.currentApp = ForegroundAppInfo("com.android.chrome", 1700000000000L)

        val result = useCase()

        assertNull(result.foregroundApp)
        assertNull(result.decision)
    }

    @Test
    fun enforcement_disabled_evaluates_as_ALLOW() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo("com.android.chrome", 1700000000000L)
        // Enforcement state: within limit (not active)
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 30,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )
        enforcementRepository.updateEnforcementState(state)

        val result = useCase()

        assertNotNull(result.foregroundApp)
        assertNotNull(result.decision)
        assertEquals(AppEnforcementAction.ALLOW, result.decision?.decision)
        assertFalse(result.decision?.enforcementActive ?: true)
        assertEquals("Screen-time enforcement is not active", result.decision?.reason)
    }

    @Test
    fun enforcement_active_with_allowed_package_evaluates_as_ALLOW() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo("com.android.settings", 1700000000000L)
        policyRepository.addAllowedPackage("com.android.settings")

        // Enforcement state: limit reached (active)
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 65,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        enforcementRepository.updateEnforcementState(state)

        val result = useCase()

        assertNotNull(result.foregroundApp)
        assertNotNull(result.decision)
        assertEquals(AppEnforcementAction.ALLOW, result.decision?.decision)
        assertTrue(result.decision?.enforcementActive ?: false)
        assertTrue(result.decision?.reason?.contains("explicitly allowed") == true)
    }

    @Test
    fun enforcement_active_with_non_allowed_package_returns_BLOCK_decision() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo("com.google.android.youtube", 1700000000000L)

        // Enforcement state: limit reached (active)
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 60,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        enforcementRepository.updateEnforcementState(state)

        val result = useCase()

        assertNotNull(result.foregroundApp)
        assertNotNull(result.decision)
        assertEquals(AppEnforcementAction.BLOCK, result.decision?.decision)
        assertTrue(result.decision?.isBlocked ?: false)
        assertTrue(result.decision?.enforcementActive ?: false)
        assertTrue(result.decision?.reason?.contains("not in allowed list") == true)
    }

    @Test
    fun niqob_package_is_always_ALLOW_even_when_enforcement_is_active() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo(AppEnforcementPolicy.NIQOB_PACKAGE_NAME, 1700000000000L)

        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 90,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        enforcementRepository.updateEnforcementState(state)

        val result = useCase()

        assertNotNull(result.foregroundApp)
        assertNotNull(result.decision)
        assertEquals(AppEnforcementAction.ALLOW, result.decision?.decision)
        assertTrue(result.decision?.isAllowed ?: false)
        assertEquals("NIQOB self application is always allowed", result.decision?.reason)
    }

    @Test
    fun block_decision_does_not_trigger_any_android_blocking_action() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo("com.android.chrome", 1700000000000L)
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 60,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        enforcementRepository.updateEnforcementState(state)

        val result = useCase()

        // Pure evaluation only: Returns BLOCK decision object, performs NO Android side-effects
        assertEquals(AppEnforcementAction.BLOCK, result.decision?.decision)
        assertTrue(result.decision?.isBlocked ?: false)
        // Detector remains unharmed
        assertEquals("com.android.chrome", fakeDetector.currentApp?.packageName)
    }

    @Test
    fun detector_supports_excluding_self_package() = runBlocking {
        fakeDetector.currentApp = ForegroundAppInfo("com.niqob.app", 1700000002000L)
        fakeDetector.previousApp = ForegroundAppInfo("com.android.chrome", 1700000001000L)

        // Without excludeSelf -> returns NIQOB
        val resultWithSelf = useCase(excludeSelf = false)
        assertEquals("com.niqob.app", resultWithSelf.foregroundApp?.packageName)
        assertEquals(AppEnforcementAction.ALLOW, resultWithSelf.decision?.decision)

        // With excludeSelf -> returns previous external app
        val resultExcludingSelf = useCase(excludeSelf = true)
        assertEquals("com.android.chrome", resultExcludingSelf.foregroundApp?.packageName)
    }

    @Test
    fun evaluate_package_direct_overload_works() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 60,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        enforcementRepository.updateEnforcementState(state)

        val chromeDecision = useCase.evaluatePackage("com.android.chrome")
        assertEquals(AppEnforcementAction.BLOCK, chromeDecision.decision)

        val niqobDecision = useCase.evaluatePackage("com.niqob.app")
        assertEquals(AppEnforcementAction.ALLOW, niqobDecision.decision)
    }
}

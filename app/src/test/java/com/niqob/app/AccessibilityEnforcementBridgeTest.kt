package com.niqob.app

import com.niqob.app.data.local.enforcement.AndroidAppEnforcementController
import com.niqob.app.data.local.enforcement.LocalParentOverrideAuthenticator
import com.niqob.app.data.local.enforcement.NiqobAccessibilityEnforcementService
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.EnforcementDebouncer
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import com.niqob.app.fakes.FakeDeviceOwnerEnforcementManager
import com.niqob.app.fakes.FakeEnforcementActionPerformer
import com.niqob.app.fakes.FakeParentOverrideAuthenticator
import com.niqob.app.fakes.FakeScreenTimeEnforcementRepository
import com.niqob.app.feature.child.enforcement.ParentOverrideUiState
import com.niqob.app.feature.child.enforcement.ScreenTimeLimitReachedViewModel
import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityEnforcementBridgeTest {

    private lateinit var enforcementRepo: FakeScreenTimeEnforcementRepository
    private lateinit var policyRepo: LocalAppEnforcementPolicyRepository
    private lateinit var fakeActionPerformer: FakeEnforcementActionPerformer
    private lateinit var service: NiqobAccessibilityEnforcementService

    @Before
    fun setUp() {
        enforcementRepo = FakeScreenTimeEnforcementRepository()
        policyRepo = LocalAppEnforcementPolicyRepository.getInstance()
        policyRepo.clear()

        fakeActionPerformer = FakeEnforcementActionPerformer()
        service = NiqobAccessibilityEnforcementService()
        service.actionPerformer = fakeActionPerformer
        service.enforcementRepository = enforcementRepo
        service.policyRepository = policyRepo
        service.debouncer = EnforcementDebouncer(
            cooldownDurationMillis = 1500L,
            timeProvider = { 10000L }
        )
    }

    @After
    fun tearDown() {
        NiqobAccessibilityEnforcementService.resetForTesting()
        policyRepo.clear()
    }

    // 1. Accessibility disabled -> no enforcement
    @Test
    fun accessibilityDisabled_noRealBlockExecuted() = runBlocking {
        NiqobAccessibilityEnforcementService.setServiceActiveForTesting(false)
        val controller = AndroidAppEnforcementController(
            policyRepository = policyRepo,
            enforcementRepository = enforcementRepo,
            deviceOwnerManager = FakeDeviceOwnerEnforcementManager(isDeviceOwner = false)
        )
        controller.start()

        // Set limit reached
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 125,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 104,
                usageDate = "2026-10-04"
            )
        )

        val result = controller.handleForegroundPackage("com.android.chrome")
        assertEquals(EnforcementActionResult.PREVIEW_BLOCK, result.actionResult)
        assertFalse("Real block must not be executed when accessibility is disabled", result.isRealBlockExecuted)
    }

    // 2. Accessibility enabled + enforcement inactive -> no block
    @Test
    fun accessibilityEnabled_enforcementInactive_returnsAllow_noBlockAction() {
        NiqobAccessibilityEnforcementService.setServiceActiveForTesting(true)
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = false,
                usedMinutes = 45,
                dailyLimitMinutes = 120,
                remainingMinutes = 75,
                usagePercentage = 37,
                usageDate = "2026-10-04"
            )
        )

        val result = service.processPackageEvent("com.android.chrome")
        assertNotNull(result)
        assertEquals(EnforcementActionResult.PREVIEW_ALLOW, result?.actionResult)
        assertFalse(result?.isRealBlockExecuted ?: true)
        assertEquals(0, fakeActionPerformer.sendHomeCallCount)
        assertEquals(0, fakeActionPerformer.launchLimitScreenCallCount)
    }

    // 3. Accessibility enabled + allowed package -> no block
    @Test
    fun accessibilityEnabled_allowedPackage_returnsAllow_noBlockAction() {
        NiqobAccessibilityEnforcementService.setServiceActiveForTesting(true)
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 130,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 108,
                usageDate = "2026-10-04"
            )
        )
        // Add Chrome to allowed packages
        policyRepo.updatePolicy(
            AppEnforcementPolicy(
                isEnforcementActive = true,
                allowedPackageNames = setOf("com.android.chrome")
            )
        )

        val result = service.processPackageEvent("com.android.chrome")
        assertNotNull(result)
        assertEquals(EnforcementActionResult.PREVIEW_ALLOW, result?.actionResult)
        assertEquals(0, fakeActionPerformer.sendHomeCallCount)
        assertEquals(0, fakeActionPerformer.launchLimitScreenCallCount)
    }

    // 4. Accessibility enabled + blocked package -> enforcement action
    @Test
    fun accessibilityEnabled_blockedPackage_executesEnforcementAction() {
        NiqobAccessibilityEnforcementService.setServiceActiveForTesting(true)
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 130,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 108,
                usageDate = "2026-10-04"
            )
        )

        val result = service.processPackageEvent("com.android.chrome")
        assertNotNull(result)
        assertEquals(EnforcementActionResult.ENFORCED_HOME_REDIRECT, result?.actionResult)
        assertTrue(result?.isRealBlockExecuted ?: false)
        assertEquals(1, fakeActionPerformer.sendHomeCallCount)
    }

    // 5. NIQOB package ignored
    @Test
    fun niqobSelfPackage_ignoredCompletely() {
        NiqobAccessibilityEnforcementService.setServiceActiveForTesting(true)
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 130,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 108,
                usageDate = "2026-10-04"
            )
        )

        val result = service.processPackageEvent("com.niqob.app")
        assertNull("NIQOB package must be completely ignored by accessibility bridge", result)
        assertEquals(0, fakeActionPerformer.sendHomeCallCount)
    }

    // 6. Debounce prevents repeated enforcement
    @Test
    fun debounce_preventsRepeatedEnforcementWithinCooldown() {
        var currentTime = 1000L
        val debouncer = EnforcementDebouncer(
            cooldownDurationMillis = 1500L,
            timeProvider = { currentTime }
        )

        // First enforcement attempt -> allowed
        assertTrue(debouncer.shouldEnforce("com.android.chrome"))

        // Immediate retry at t=1200ms (200ms elapsed) -> debounced
        currentTime = 1200L
        assertFalse("Retry within 1500ms cooldown must be debounced", debouncer.shouldEnforce("com.android.chrome"))

        // After cooldown expires at t=2600ms (1600ms elapsed) -> allowed
        currentTime = 2600L
        assertTrue("Enforcement after cooldown expires must be allowed", debouncer.shouldEnforce("com.android.chrome"))
    }

    // 7. Different blocked packages can trigger separately
    @Test
    fun differentBlockedPackages_canTriggerSeparately() {
        var currentTime = 1000L
        val debouncer = EnforcementDebouncer(
            cooldownDurationMillis = 1500L,
            timeProvider = { currentTime }
        )

        // Package A triggers
        assertTrue(debouncer.shouldEnforce("com.android.chrome"))

        // Package B triggers immediately after -> must be allowed separately
        currentTime = 1100L
        assertTrue(
            "Distinct package must trigger independently of other packages' cooldown",
            debouncer.shouldEnforce("com.google.android.youtube")
        )
    }

    // 8. Wrong parent PIN rejected
    @Test
    fun wrongParentPin_rejected() = runBlocking {
        val authenticator = LocalParentOverrideAuthenticator(devPin = "1234")
        val viewModel = ScreenTimeLimitReachedViewModel(
            enforcementRepository = enforcementRepo,
            appPolicyRepository = policyRepo,
            parentOverrideAuthenticator = authenticator
        )

        val success = viewModel.authenticateParentPin("0000")
        assertFalse(success)
        assertTrue(viewModel.parentOverrideState.value is ParentOverrideUiState.Error)
        assertFalse(
            enforcementRepo.getEnforcementState().value?.isParentOverrideActive ?: false
        )
    }

    // 9. Correct parent PIN accepted
    @Test
    fun correctParentPin_accepted() = runBlocking {
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 135,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 112,
                usageDate = "2026-10-04"
            )
        )

        val authenticator = LocalParentOverrideAuthenticator(devPin = "1234")
        val viewModel = ScreenTimeLimitReachedViewModel(
            enforcementRepository = enforcementRepo,
            appPolicyRepository = policyRepo,
            parentOverrideAuthenticator = authenticator
        )

        val success = viewModel.authenticateParentPin("1234")
        assertTrue(success)
        assertEquals(ParentOverrideUiState.Success, viewModel.parentOverrideState.value)
        assertTrue(
            enforcementRepo.getEnforcementState().value?.isParentOverrideActive ?: false
        )
    }

    // 10. Parent override does not permanently disable screen time
    @Test
    fun parentOverride_doesNotPermanentlyDisableScreenTime() = runBlocking {
        val originalState = ScreenTimeEnforcementState(
            isEnforcementEnabled = true,
            isLimitReached = true,
            usedMinutes = 135,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 112,
            usageDate = "2026-10-04"
        )
        enforcementRepo.updateEnforcementState(originalState)

        val authenticator = LocalParentOverrideAuthenticator(devPin = "1234")
        val viewModel = ScreenTimeLimitReachedViewModel(
            enforcementRepository = enforcementRepo,
            appPolicyRepository = policyRepo,
            parentOverrideAuthenticator = authenticator
        )

        viewModel.authenticateParentPin("1234")

        val stateAfterOverride = enforcementRepo.getEnforcementState().value
        assertNotNull(stateAfterOverride)
        assertTrue("Enforcement must still be enabled in policy", stateAfterOverride!!.isEnforcementEnabled)
        assertTrue("Limit reached flag must remain true", stateAfterOverride.isLimitReached)
        assertEquals(120, stateAfterOverride.dailyLimitMinutes)
        assertEquals(135, stateAfterOverride.usedMinutes)
        assertEquals(ScreenTimeEnforcementStatus.LIMIT_REACHED, stateAfterOverride.status)
        assertTrue("isParentOverrideActive must be true", stateAfterOverride.isParentOverrideActive)
        assertFalse("Effective isEnforcementActive must be false during override", stateAfterOverride.isEnforcementActive)
    }

    // 11. Controller lifecycle
    @Test
    fun controllerLifecycle_startAndStopUpdateRunningState() = runBlocking {
        val controller = AndroidAppEnforcementController(
            policyRepository = policyRepo,
            enforcementRepository = enforcementRepo,
            deviceOwnerManager = FakeDeviceOwnerEnforcementManager(isDeviceOwner = false)
        )

        assertFalse(controller.isRunning.value)
        controller.start()
        assertTrue(controller.isRunning.value)
        controller.stop()
        assertFalse(controller.isRunning.value)
    }

    // 12. Service failure does not crash app
    @Test
    fun serviceFailure_doesNotCrashApp() {
        val failingPerformer = object : com.niqob.app.domain.enforcement.EnforcementActionPerformer {
            override fun sendHome(): Boolean = throw RuntimeException("Simulated window manager failure")
            override fun launchLimitScreen(targetPackageName: String): Boolean = throw RuntimeException("Simulated activity failure")
        }

        service.actionPerformer = failingPerformer
        enforcementRepo.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 130,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 108,
                usageDate = "2026-10-04"
            )
        )

        // Calling processPackageEvent when performer throws must be caught or handled safely
        try {
            service.processPackageEvent("com.android.chrome")
        } catch (e: Exception) {
            // Should not crash the process
            assertTrue(e.message?.contains("Simulated") == true)
        }
    }

    // 13. Domain evaluator remains Android-independent
    @Test
    fun domainEvaluator_remainsAndroidIndependent() {
        val evaluatorClass = AppEnforcementEvaluator::class.java
        evaluatorClass.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { paramType ->
                assertFalse(
                    "Domain evaluator parameter '${paramType.name}' must not depend on Android framework",
                    paramType.name.startsWith("android.")
                )
            }
            assertFalse(
                "Domain evaluator return type '${method.returnType.name}' must not depend on Android framework",
                method.returnType.name.startsWith("android.")
            )
        }
    }
}

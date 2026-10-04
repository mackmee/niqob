package com.niqob.app

import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.fakes.FakeAppEnforcementController
import com.niqob.app.fakes.FakeDeviceOwnerEnforcementManager
import com.niqob.app.fakes.FakeScreenTimeEnforcementRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying Phase 4D-2 enforcement architecture, investigation criteria,
 * and safety guardrails.
 */
class AppEnforcementInvestigationTest {

    private lateinit var policyRepository: LocalAppEnforcementPolicyRepository
    private lateinit var enforcementRepository: FakeScreenTimeEnforcementRepository
    private lateinit var fakeDeviceOwnerManager: FakeDeviceOwnerEnforcementManager
    private lateinit var controller: FakeAppEnforcementController

    @Before
    fun setUp() {
        policyRepository = LocalAppEnforcementPolicyRepository()
        enforcementRepository = FakeScreenTimeEnforcementRepository()
        fakeDeviceOwnerManager = FakeDeviceOwnerEnforcementManager(isDeviceOwner = false)
        controller = FakeAppEnforcementController(
            policyRepository = policyRepository,
            enforcementRepository = enforcementRepository,
            isDeviceOwnerSimulated = false
        )
    }

    @Test
    fun controller_startAndStop_lifecycleIsSafe() = runBlocking {
        assertFalse(controller.isRunning.value)
        assertEquals(0, controller.startCallCount)

        controller.start()
        assertTrue(controller.isRunning.value)
        assertEquals(1, controller.startCallCount)

        controller.stop()
        assertFalse(controller.isRunning.value)
        assertEquals(1, controller.stopCallCount)
    }

    @Test
    fun controller_whenStopped_returnsNotSupported() = runBlocking {
        // Controller is stopped
        val result = controller.handleForegroundPackage("com.android.chrome")
        assertEquals(EnforcementActionResult.NOT_SUPPORTED, result.actionResult)
        assertFalse(result.isRealBlockExecuted)
    }

    @Test
    fun enforcementInactive_returnsPreviewAllow() = runBlocking {
        controller.start()
        enforcementRepository.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = false,
                isLimitReached = false,
                usedMinutes = 30,
                dailyLimitMinutes = 120,
                remainingMinutes = 90,
                usagePercentage = 25,
                usageDate = "2026-10-04"
            )
        )

        val result = controller.handleForegroundPackage("com.android.chrome")
        assertEquals(EnforcementActionResult.PREVIEW_ALLOW, result.actionResult)
        assertEquals(AppEnforcementAction.ALLOW, result.decision?.decision)
        assertFalse(result.isRealBlockExecuted)
    }

    @Test
    fun enforcementActive_allowedPackage_returnsPreviewAllow() = runBlocking {
        controller.start()
        enforcementRepository.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 125,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 100,
                usageDate = "2026-10-04"
            )
        )
        policyRepository.addAllowedPackage("com.example.learning")

        val result = controller.handleForegroundPackage("com.example.learning")
        assertEquals(EnforcementActionResult.PREVIEW_ALLOW, result.actionResult)
        assertEquals(AppEnforcementAction.ALLOW, result.decision?.decision)
        assertFalse(result.isRealBlockExecuted)
    }

    @Test
    fun enforcementActive_blockedPackage_returnsPreviewBlock() = runBlocking {
        controller.start()
        enforcementRepository.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 125,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 100,
                usageDate = "2026-10-04"
            )
        )

        val result = controller.handleForegroundPackage("com.android.chrome")
        assertEquals(EnforcementActionResult.PREVIEW_BLOCK, result.actionResult)
        assertEquals(AppEnforcementAction.BLOCK, result.decision?.decision)
        assertFalse(result.isRealBlockExecuted)
    }

    @Test
    fun niqobSelfPackage_alwaysEvaluatesPreviewAllow() = runBlocking {
        controller.start()
        enforcementRepository.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 200,
                dailyLimitMinutes = 60,
                remainingMinutes = 0,
                usagePercentage = 100,
                usageDate = "2026-10-04"
            )
        )

        val result = controller.handleForegroundPackage(AppEnforcementPolicy.NIQOB_PACKAGE_NAME)
        assertEquals(EnforcementActionResult.PREVIEW_ALLOW, result.actionResult)
        assertEquals(AppEnforcementAction.ALLOW, result.decision?.decision)
        assertEquals("NIQOB self application is always allowed", result.decision?.reason)
        assertFalse(result.isRealBlockExecuted)
    }

    @Test
    fun unsupportedDeviceMechanism_returnsNotSupported() = runBlocking {
        // Device Owner is not provisioned on this BYOD phone
        fakeDeviceOwnerManager.isDeviceOwner = false
        val result = fakeDeviceOwnerManager.suspendPackages(listOf("com.android.chrome"))

        assertEquals(EnforcementActionResult.NOT_SUPPORTED, result.actionResult)
        assertFalse(result.isRealBlockExecuted)
        assertTrue(fakeDeviceOwnerManager.suspendedPackages.isEmpty())
    }

    @Test
    fun previewMode_guaranteesNoRealBlockExecuted() = runBlocking {
        controller.start()
        enforcementRepository.updateEnforcementState(
            ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 150,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 100,
                usageDate = "2026-10-04"
            )
        )

        val testPackages = listOf(
            "com.android.chrome",
            "com.google.android.youtube",
            "com.instagram.android",
            "com.niqob.app"
        )

        for (pkg in testPackages) {
            val result = controller.handleForegroundPackage(pkg)
            assertFalse("Real block must never be executed in prototype for $pkg", result.isRealBlockExecuted)
        }
    }

    @Test
    fun domainEvaluator_remainsPureKotlin_noAndroidDependencies() {
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = ScreenTimeEnforcementState(
                isEnforcementEnabled = true,
                isLimitReached = true,
                usedMinutes = 130,
                dailyLimitMinutes = 120,
                remainingMinutes = 0,
                usagePercentage = 100,
                usageDate = "2026-10-04"
            ),
            policy = AppEnforcementPolicy.DEFAULT
        )
        assertNotNull(decision)
        assertEquals(AppEnforcementAction.BLOCK, decision.decision)

        // Verify class loader and absence of Android runtime classes
        val evaluatorClass = AppEnforcementEvaluator::class.java
        val imports = evaluatorClass.declaredMethods
        for (m in imports) {
            for (p in m.parameterTypes) {
                assertFalse(p.name.startsWith("android.view."))
                assertFalse(p.name.startsWith("android.accessibilityservice."))
                assertFalse(p.name.startsWith("android.app.admin."))
            }
        }
    }

    @Test
    fun deviceOwnerManager_whenActive_handlesSuspensionInPreview() = runBlocking {
        fakeDeviceOwnerManager.isDeviceOwner = true
        val result = fakeDeviceOwnerManager.suspendPackages(listOf("com.android.chrome"))

        assertEquals(EnforcementActionResult.PREVIEW_BLOCK, result.actionResult)
        assertTrue(fakeDeviceOwnerManager.suspendedPackages.contains("com.android.chrome"))

        val unsuspendResult = fakeDeviceOwnerManager.unsuspendPackages(listOf("com.android.chrome"))
        assertEquals(EnforcementActionResult.PREVIEW_ALLOW, unsuspendResult.actionResult)
        assertFalse(fakeDeviceOwnerManager.suspendedPackages.contains("com.android.chrome"))
    }
}

package com.niqob.app.fakes

import com.niqob.app.domain.enforcement.AppEnforcementController
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.EnforcementExecutionResult
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake implementation of [AppEnforcementController] for pure JVM unit tests.
 */
class FakeAppEnforcementController(
    private val policyRepository: AppEnforcementPolicyRepository,
    private val enforcementRepository: ScreenTimeEnforcementRepository,
    var isDeviceOwnerSimulated: Boolean = false
) : AppEnforcementController {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    var startCallCount = 0
    var stopCallCount = 0
    var lastHandledPackage: String? = null

    override suspend fun start() {
        startCallCount++
        _isRunning.value = true
    }

    override suspend fun stop() {
        stopCallCount++
        _isRunning.value = false
    }

    override suspend fun handleForegroundPackage(packageName: String): EnforcementExecutionResult {
        lastHandledPackage = packageName
        if (!_isRunning.value) {
            return EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.NOT_SUPPORTED,
                mechanismName = if (isDeviceOwnerSimulated) "Device Owner" else "AccessibilityService",
                message = "Enforcement controller is stopped.",
                isRealBlockExecuted = false
            )
        }

        val policy = policyRepository.getPolicy().value
        val enforcementState = enforcementRepository.getEnforcementState().value

        val decision = AppEnforcementEvaluator.evaluate(
            packageName = packageName,
            enforcementState = enforcementState,
            policy = policy
        )

        val mechanismName = if (isDeviceOwnerSimulated) "Device Owner (Managed Device)" else "AccessibilityService (BYOD Prototype)"

        return if (decision.decision == AppEnforcementAction.ALLOW) {
            EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.PREVIEW_ALLOW,
                decision = decision,
                mechanismName = mechanismName,
                message = "Preview: Application '${decision.packageName}' is allowed.",
                isRealBlockExecuted = false,
                timestampMillis = decision.timestampMillis
            )
        } else {
            EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.PREVIEW_BLOCK,
                decision = decision,
                mechanismName = mechanismName,
                message = "Preview only: Application '${decision.packageName}' evaluates to BLOCK. Real blocking is not performed.",
                isRealBlockExecuted = false,
                timestampMillis = decision.timestampMillis
            )
        }
    }
}

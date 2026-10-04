package com.niqob.app.data.local.enforcement

import com.niqob.app.domain.enforcement.AppEnforcementController
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.DeviceOwnerEnforcementManager
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.EnforcementExecutionResult
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [AppEnforcementController].
 *
 * Coordinates domain policy evaluation with platform-specific mechanisms.
 *
 * SAFETY GUARANTEES:
 * - Does NOT call `killProcess` or `forceStopPackage`.
 * - Does NOT launch blocking overlays or intercept system navigation buttons.
 * - Supports safe start/stop lifecycle.
 */
class AndroidAppEnforcementController(
    private val policyRepository: AppEnforcementPolicyRepository,
    private val enforcementRepository: ScreenTimeEnforcementRepository,
    private val deviceOwnerManager: DeviceOwnerEnforcementManager
) : AppEnforcementController {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override suspend fun start() {
        _isRunning.value = true
        NiqobAccessibilityEnforcementService.activeController = this
    }

    override suspend fun stop() {
        _isRunning.value = false
        if (NiqobAccessibilityEnforcementService.activeController === this) {
            NiqobAccessibilityEnforcementService.activeController = null
        }
    }

    override suspend fun handleForegroundPackage(packageName: String): EnforcementExecutionResult {
        if (!_isRunning.value) {
            return EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.NOT_SUPPORTED,
                mechanismName = determineMechanismName(),
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

        val mechanismName = determineMechanismName()
        val isAccessibilityActive = NiqobAccessibilityEnforcementService.isServiceActive.value

        return if (decision.decision == AppEnforcementAction.ALLOW) {
            EnforcementExecutionResult(
                packageName = packageName,
                actionResult = EnforcementActionResult.PREVIEW_ALLOW,
                decision = decision,
                mechanismName = mechanismName,
                message = "Application '${decision.packageName}' is allowed by policy.",
                isRealBlockExecuted = false,
                timestampMillis = decision.timestampMillis
            )
        } else {
            // Target is BLOCK
            if (isAccessibilityActive) {
                EnforcementExecutionResult(
                    packageName = packageName,
                    actionResult = EnforcementActionResult.ENFORCED_HOME_REDIRECT,
                    decision = decision,
                    mechanismName = mechanismName,
                    message = "Application '${decision.packageName}' is blocked. Accessibility bridge sends Home & displays gate.",
                    isRealBlockExecuted = true,
                    timestampMillis = decision.timestampMillis
                )
            } else {
                EnforcementExecutionResult(
                    packageName = packageName,
                    actionResult = EnforcementActionResult.PREVIEW_BLOCK,
                    decision = decision,
                    mechanismName = mechanismName,
                    message = "Application '${decision.packageName}' evaluates to BLOCK, but AccessibilityService is disabled.",
                    isRealBlockExecuted = false,
                    timestampMillis = decision.timestampMillis
                )
            }
        }
    }

    private fun determineMechanismName(): String {
        return if (deviceOwnerManager.isDeviceOwnerActive()) {
            "Device Owner (Managed Device)"
        } else {
            "AccessibilityService (BYOD)"
        }
    }
}

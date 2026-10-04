package com.niqob.app.domain.usecase

import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.enforcement.ForegroundAppDetector
import com.niqob.app.domain.model.AppEnforcementDecision
import com.niqob.app.domain.model.ForegroundAppInfo
import com.niqob.app.domain.repository.AppEnforcementPolicyRepository
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository

/**
 * Encapsulates the evaluation result of the current foreground application.
 */
data class ForegroundEvaluationResult(
    val foregroundApp: ForegroundAppInfo?,
    val decision: AppEnforcementDecision?
)

/**
 * Use case to evaluate the currently detected foreground application against
 * the active screen-time enforcement state and app enforcement policy.
 *
 * This use case is 100% pure Kotlin with zero Android framework dependencies.
 * It is an OBSERVATION and EVALUATION pipeline only. It does not block applications,
 * kill processes, show overlays, or alter system behavior.
 */
class EvaluateForegroundAppUseCase(
    private val foregroundAppDetector: ForegroundAppDetector,
    private val policyRepository: AppEnforcementPolicyRepository,
    private val enforcementRepository: ScreenTimeEnforcementRepository
) {

    /**
     * Detects and evaluates the current foreground application.
     *
     * @param excludeSelf If true, ignores NIQOB's own package name to evaluate
     *                    the external foreground application that was active prior to NIQOB.
     */
    suspend operator fun invoke(excludeSelf: Boolean = false): ForegroundEvaluationResult {
        val foregroundApp = foregroundAppDetector.getCurrentForegroundApp(excludeSelf)
        if (foregroundApp == null || foregroundApp.packageName.isBlank()) {
            return ForegroundEvaluationResult(
                foregroundApp = null,
                decision = null
            )
        }

        val policy = policyRepository.getPolicy().value
        val enforcementState = enforcementRepository.getEnforcementState().value

        val decision = AppEnforcementEvaluator.evaluate(
            packageName = foregroundApp.packageName,
            enforcementState = enforcementState,
            policy = policy,
            timestampMillis = foregroundApp.timestampMillis
        )

        return ForegroundEvaluationResult(
            foregroundApp = foregroundApp,
            decision = decision
        )
    }

    /**
     * Evaluates a specific package name against the current policy and enforcement state.
     */
    fun evaluatePackage(
        packageName: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): AppEnforcementDecision {
        val policy = policyRepository.getPolicy().value
        val enforcementState = enforcementRepository.getEnforcementState().value

        return AppEnforcementEvaluator.evaluate(
            packageName = packageName,
            enforcementState = enforcementState,
            policy = policy,
            timestampMillis = timestampMillis
        )
    }
}

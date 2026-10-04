package com.niqob.app.domain.model

/**
 * Result of an app enforcement action execution.
 *
 * NOTE: For the Phase 4D-2 prototype, all actions evaluate as preview, not supported, or error.
 * An app must never be claimed to be actually blocked unless Android genuinely prevented access.
 */
enum class EnforcementActionResult {
    PREVIEW_ALLOW,
    PREVIEW_BLOCK,
    ENFORCED_HOME_REDIRECT,
    NOT_SUPPORTED,
    ERROR
}

/**
 * Detailed outcome of an enforcement action or evaluation.
 *
 * @property packageName The package evaluated.
 * @property actionResult The execution result ([EnforcementActionResult]).
 * @property decision The underlying policy evaluation decision, if available.
 * @property mechanismName The mechanism evaluated (e.g. "AccessibilityService (BYOD)", "Device Owner (Managed)").
 * @property message Informational explanation of the result.
 * @property isRealBlockExecuted Whether an actual OS-level block occurred (strictly false for prototype).
 * @property timestampMillis Epoch timestamp when this result was produced.
 */
data class EnforcementExecutionResult(
    val packageName: String,
    val actionResult: EnforcementActionResult,
    val decision: AppEnforcementDecision? = null,
    val mechanismName: String = "Prototype",
    val message: String = "",
    val isRealBlockExecuted: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)

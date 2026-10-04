package com.niqob.app.domain.model

/**
 * Pure domain model representing the policy evaluation outcome for a specific package.
 *
 * @property packageName The Android package name being evaluated.
 * @property decision The enforcement action (ALLOW or BLOCK).
 * @property reason Human-readable explanation of why this decision was reached.
 * @property enforcementActive Whether screen-time enforcement is currently active.
 * @property timestampMillis Epoch timestamp when this decision was evaluated.
 */
data class AppEnforcementDecision(
    val packageName: String,
    val decision: AppEnforcementAction,
    val reason: String,
    val enforcementActive: Boolean,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    val isAllowed: Boolean
        get() = decision == AppEnforcementAction.ALLOW

    val isBlocked: Boolean
        get() = decision == AppEnforcementAction.BLOCK
}

package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementDecision
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ScreenTimeEnforcementState

/**
 * Pure domain evaluator that determines whether a given foreground application
 * package is allowed or blocked based on screen-time enforcement state and policy.
 *
 * This evaluator is 100% pure Kotlin with zero Android framework or UI dependencies.
 *
 * Decision Rules:
 * Rule D: NIQOB's own package is ALWAYS allowed so the child can reach the enforcement UI.
 * Rule A: If screen-time enforcement is NOT active -> ALLOW any application.
 * Explicit Block: If package is in policy's blocked list -> BLOCK.
 * Rule B: If package is explicitly in policy's allowed list -> ALLOW.
 * Rule C: If screen-time enforcement is active and package is not allowed -> BLOCK.
 *
 * Matching is exact string matching. Similar package names (e.g. suffixes or prefixes)
 * do not accidentally match.
 */
object AppEnforcementEvaluator {

    /**
     * Evaluates the enforcement decision for a specific package.
     *
     * @param packageName The package name to evaluate (e.g. "com.android.chrome").
     * @param enforcementState The current screen-time enforcement state, or null if uninitialized.
     * @param policy The application enforcement policy defining allowed and blocked packages.
     * @param timestampMillis Optional evaluation timestamp (defaults to current time).
     */
    fun evaluate(
        packageName: String,
        enforcementState: ScreenTimeEnforcementState?,
        policy: AppEnforcementPolicy = AppEnforcementPolicy.DEFAULT,
        timestampMillis: Long = System.currentTimeMillis()
    ): AppEnforcementDecision {
        val trimmedPackage = packageName.trim()

        val isEnforcementActive = (enforcementState?.isEnforcementActive(timestampMillis) == true) || policy.isEnforcementActive

        // Rule D: NIQOB's own package is ALWAYS allowed
        if (trimmedPackage == AppEnforcementPolicy.NIQOB_PACKAGE_NAME) {
            return AppEnforcementDecision(
                packageName = trimmedPackage,
                decision = AppEnforcementAction.ALLOW,
                reason = "NIQOB self application is always allowed",
                enforcementActive = isEnforcementActive,
                timestampMillis = timestampMillis
            )
        }

        // Rule A: If enforcement is NOT active -> ALLOW
        if (!isEnforcementActive) {
            return AppEnforcementDecision(
                packageName = trimmedPackage,
                decision = AppEnforcementAction.ALLOW,
                reason = "Screen-time enforcement is not active",
                enforcementActive = false,
                timestampMillis = timestampMillis
            )
        }

        // Explicit Block Rule
        if (policy.blockedPackageNames.contains(trimmedPackage)) {
            return AppEnforcementDecision(
                packageName = trimmedPackage,
                decision = AppEnforcementAction.BLOCK,
                reason = "Package '$trimmedPackage' is explicitly blocked by policy",
                enforcementActive = true,
                timestampMillis = timestampMillis
            )
        }

        // Rule B: Explicitly allowed package -> ALLOW
        if (policy.allowedPackageNames.contains(trimmedPackage)) {
            return AppEnforcementDecision(
                packageName = trimmedPackage,
                decision = AppEnforcementAction.ALLOW,
                reason = "Package '$trimmedPackage' is explicitly allowed by policy",
                enforcementActive = true,
                timestampMillis = timestampMillis
            )
        }

        // Rule C: Enforcement is active and package is not allowed -> BLOCK
        return AppEnforcementDecision(
            packageName = trimmedPackage,
            decision = AppEnforcementAction.BLOCK,
            reason = "Screen-time limit reached; package '$trimmedPackage' is not in allowed list",
            enforcementActive = true,
            timestampMillis = timestampMillis
        )
    }
}

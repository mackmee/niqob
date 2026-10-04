package com.niqob.app.domain.model

/**
 * Enforcement policy mode to configure how applications are filtered when enforcement is active.
 */
enum class AppEnforcementPolicyMode {
    DEFAULT,
    STRICT_ALLOWLIST,
    BLOCKLIST_ONLY
}

/**
 * Pure domain representation of the application enforcement policy.
 *
 * @property isEnforcementActive Whether enforcement is considered active in the policy.
 * @property allowedPackageNames Set of package names that are explicitly allowed even when enforcement is active.
 * @property blockedPackageNames Set of package names that are explicitly forbidden.
 * @property policyMode Policy evaluation mode.
 */
data class AppEnforcementPolicy(
    val isEnforcementActive: Boolean = false,
    val allowedPackageNames: Set<String> = emptySet(),
    val blockedPackageNames: Set<String> = emptySet(),
    val policyMode: AppEnforcementPolicyMode = AppEnforcementPolicyMode.DEFAULT
) {
    companion object {
        const val NIQOB_PACKAGE_NAME = "com.niqob.app"

        val DEFAULT = AppEnforcementPolicy(
            isEnforcementActive = false,
            allowedPackageNames = emptySet(),
            blockedPackageNames = emptySet(),
            policyMode = AppEnforcementPolicyMode.DEFAULT
        )
    }
}

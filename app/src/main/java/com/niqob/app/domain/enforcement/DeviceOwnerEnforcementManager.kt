package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.EnforcementExecutionResult

/**
 * Domain interface representing managed-device / Device Owner capabilities.
 *
 * For dedicated NIQOB child devices, Device Owner is the recommended production architecture
 * because it provides native OS-level app suspension (via DevicePolicyManager.setPackagesSuspended)
 * without battery drain, polling, or visual overlays.
 *
 * On unmanaged / BYOD personal devices, [isDeviceOwnerActive] returns false,
 * and calls safely result in [com.niqob.app.domain.model.EnforcementActionResult.NOT_SUPPORTED].
 */
interface DeviceOwnerEnforcementManager {

    /**
     * Returns true if NIQOB is currently active as the Android Device Owner.
     */
    fun isDeviceOwnerActive(): Boolean

    /**
     * Suspends the specified packages using native OS enterprise APIs.
     *
     * @param packages List of package names to suspend.
     * @return [EnforcementExecutionResult] with the outcome.
     */
    suspend fun suspendPackages(packages: List<String>): EnforcementExecutionResult

    /**
     * Unsuspends the specified packages using native OS enterprise APIs.
     *
     * @param packages List of package names to unsuspend.
     * @return [EnforcementExecutionResult] with the outcome.
     */
    suspend fun unsuspendPackages(packages: List<String>): EnforcementExecutionResult
}

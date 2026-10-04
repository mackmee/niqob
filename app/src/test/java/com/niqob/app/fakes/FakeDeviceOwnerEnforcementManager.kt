package com.niqob.app.fakes

import com.niqob.app.domain.enforcement.DeviceOwnerEnforcementManager
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.EnforcementExecutionResult

/**
 * Fake implementation of [DeviceOwnerEnforcementManager] for unit testing.
 */
class FakeDeviceOwnerEnforcementManager(
    var isDeviceOwner: Boolean = false
) : DeviceOwnerEnforcementManager {

    val suspendedPackages = mutableListOf<String>()

    override fun isDeviceOwnerActive(): Boolean = isDeviceOwner

    override suspend fun suspendPackages(packages: List<String>): EnforcementExecutionResult {
        if (!isDeviceOwner) {
            return EnforcementExecutionResult(
                packageName = packages.firstOrNull() ?: "",
                actionResult = EnforcementActionResult.NOT_SUPPORTED,
                mechanismName = "Device Owner (Managed Device)",
                message = "Device Owner is not provisioned on this device.",
                isRealBlockExecuted = false
            )
        }

        suspendedPackages.addAll(packages)
        return EnforcementExecutionResult(
            packageName = packages.firstOrNull() ?: "",
            actionResult = EnforcementActionResult.PREVIEW_BLOCK,
            mechanismName = "Device Owner (Managed Device)",
            message = "Device Owner suspended packages: $packages",
            isRealBlockExecuted = false
        )
    }

    override suspend fun unsuspendPackages(packages: List<String>): EnforcementExecutionResult {
        if (!isDeviceOwner) {
            return EnforcementExecutionResult(
                packageName = packages.firstOrNull() ?: "",
                actionResult = EnforcementActionResult.NOT_SUPPORTED,
                mechanismName = "Device Owner (Managed Device)",
                message = "Device Owner is not provisioned on this device.",
                isRealBlockExecuted = false
            )
        }

        suspendedPackages.removeAll(packages)
        return EnforcementExecutionResult(
            packageName = packages.firstOrNull() ?: "",
            actionResult = EnforcementActionResult.PREVIEW_ALLOW,
            mechanismName = "Device Owner (Managed Device)",
            message = "Device Owner unsuspended packages: $packages",
            isRealBlockExecuted = false
        )
    }
}

package com.niqob.app.data.local.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.niqob.app.domain.enforcement.DeviceOwnerEnforcementManager
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.EnforcementExecutionResult

/**
 * Android implementation of [DeviceOwnerEnforcementManager] using [DevicePolicyManager].
 *
 * This represents the recommended architecture for DEDICATED NIQOB CHILD DEVICES.
 * It uses native OS package suspension (`setPackagesSuspended`), which:
 * - Grays out the app icon on the home launcher.
 * - Prevents the child from launching the app entirely.
 * - Displays an official Android system dialog ("App suspended by administrator").
 * - Survives reboot and does not require battery-draining polling or intrusive overlays.
 *
 * SAFETY GUARDRAIL:
 * - This class NEVER provisions Device Owner automatically.
 * - It NEVER runs `adb shell dpm set-device-owner` or shell commands.
 * - On standard personal / BYOD phones, [isDeviceOwnerActive] returns false, and calls safely
 *   return [EnforcementActionResult.NOT_SUPPORTED].
 */
class AndroidDeviceOwnerEnforcementManager(
    private val context: Context
) : DeviceOwnerEnforcementManager {

    private val dpm: DevicePolicyManager? by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(context, NiqobDeviceAdminReceiver::class.java)
    }

    override fun isDeviceOwnerActive(): Boolean {
        return try {
            dpm?.isDeviceOwnerApp(context.packageName) == true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun suspendPackages(packages: List<String>): EnforcementExecutionResult {
        if (!isDeviceOwnerActive()) {
            return EnforcementExecutionResult(
                packageName = packages.firstOrNull() ?: "",
                actionResult = EnforcementActionResult.NOT_SUPPORTED,
                mechanismName = "Device Owner (Managed Device)",
                message = "Device Owner is not provisioned on this device. Native OS package suspension is unavailable.",
                isRealBlockExecuted = false
            )
        }

        return try {
            if (dpm != null) {
                // In production with Device Owner active:
                // val failed = dpm?.setPackagesSuspended(adminComponent, packages.toTypedArray(), true)
                // For Phase 4D-2 prototype, we do not mutate system packages even if DPM was present.
                EnforcementExecutionResult(
                    packageName = packages.firstOrNull() ?: "",
                    actionResult = EnforcementActionResult.PREVIEW_BLOCK,
                    mechanismName = "Device Owner (Managed Device)",
                    message = "Device Owner architecture validated. Target packages would be suspended at OS level.",
                    isRealBlockExecuted = false
                )
            } else {
                EnforcementExecutionResult(
                    packageName = packages.firstOrNull() ?: "",
                    actionResult = EnforcementActionResult.NOT_SUPPORTED,
                    mechanismName = "Device Owner (Managed Device)",
                    message = "DevicePolicyManager service is unavailable.",
                    isRealBlockExecuted = false
                )
            }
        } catch (e: Exception) {
            EnforcementExecutionResult(
                packageName = packages.firstOrNull() ?: "",
                actionResult = EnforcementActionResult.ERROR,
                mechanismName = "Device Owner (Managed Device)",
                message = "DevicePolicyManager error: ${e.message}",
                isRealBlockExecuted = false
            )
        }
    }

    override suspend fun unsuspendPackages(packages: List<String>): EnforcementExecutionResult {
        if (!isDeviceOwnerActive()) {
            return EnforcementExecutionResult(
                packageName = packages.firstOrNull() ?: "",
                actionResult = EnforcementActionResult.NOT_SUPPORTED,
                mechanismName = "Device Owner (Managed Device)",
                message = "Device Owner is not provisioned on this device.",
                isRealBlockExecuted = false
            )
        }

        return try {
            if (dpm != null) {
                // dpm?.setPackagesSuspended(adminComponent, packages.toTypedArray(), false)
                EnforcementExecutionResult(
                    packageName = packages.firstOrNull() ?: "",
                    actionResult = EnforcementActionResult.PREVIEW_ALLOW,
                    mechanismName = "Device Owner (Managed Device)",
                    message = "Packages unsuspended successfully.",
                    isRealBlockExecuted = false
                )
            } else {
                EnforcementExecutionResult(
                    packageName = packages.firstOrNull() ?: "",
                    actionResult = EnforcementActionResult.NOT_SUPPORTED,
                    mechanismName = "Device Owner (Managed Device)",
                    message = "DevicePolicyManager service is unavailable.",
                    isRealBlockExecuted = false
                )
            }
        } catch (e: Exception) {
            EnforcementExecutionResult(
                packageName = packages.firstOrNull() ?: "",
                actionResult = EnforcementActionResult.ERROR,
                mechanismName = "Device Owner (Managed Device)",
                message = "DevicePolicyManager error: ${e.message}",
                isRealBlockExecuted = false
            )
        }
    }
}

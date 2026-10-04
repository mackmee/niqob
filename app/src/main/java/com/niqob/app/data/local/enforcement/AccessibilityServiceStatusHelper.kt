package com.niqob.app.data.local.enforcement

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.niqob.app.domain.model.AccessibilityEnforcementStatus

/**
 * Utility to verify whether [NiqobAccessibilityEnforcementService] is currently
 * active and authorized by the user in Android Accessibility settings.
 */
object AccessibilityServiceStatusHelper {

    /**
     * Checks the accessibility service status using both runtime service state
     * and system Secure Settings.
     */
    fun checkStatus(context: Context): AccessibilityEnforcementStatus {
        return try {
            // First check if service instance is currently bound and active
            if (NiqobAccessibilityEnforcementService.isServiceActive.value) {
                return AccessibilityEnforcementStatus.ENABLED
            }

            // Fallback check against Android Secure settings
            val expectedComponentName = ComponentName(context, NiqobAccessibilityEnforcementService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return AccessibilityEnforcementStatus.DISABLED

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return AccessibilityEnforcementStatus.ENABLED
                }
            }

            AccessibilityEnforcementStatus.DISABLED
        } catch (_: Exception) {
            AccessibilityEnforcementStatus.ERROR
        }
    }

    /**
     * Creates an intent to open Android Accessibility settings.
     */
    fun createOpenSettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}

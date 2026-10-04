package com.niqob.app.domain.model

/**
 * Status of the BYOD Accessibility Enforcement Bridge.
 *
 * Enforcement on BYOD is only practically active when the parent has manually
 * enabled the service in Android Settings.
 */
enum class AccessibilityEnforcementStatus(val displayName: String) {
    UNAVAILABLE("Unavailable"),
    DISABLED("Disabled"),
    ENABLED("Enabled"),
    ERROR("Error")
}

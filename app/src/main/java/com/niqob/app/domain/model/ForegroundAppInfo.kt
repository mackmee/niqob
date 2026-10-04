package com.niqob.app.domain.model

/**
 * Domain representation of a detected foreground application.
 *
 * This model contains only the package name and detection timestamp.
 * It does not contain message contents, notifications, screen data, or any PII.
 */
data class ForegroundAppInfo(
    val packageName: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

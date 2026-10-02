package com.niqob.app.domain.model

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

data class SafetyAlert(
    val id: String,
    val title: String,
    val description: String,
    val timestampMillis: Long,
    val severity: AlertSeverity
)

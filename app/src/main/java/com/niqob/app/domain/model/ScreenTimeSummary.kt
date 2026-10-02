package com.niqob.app.domain.model

data class ScreenTimeSummary(
    val dailyLimitMinutes: Int = 120,
    val usedMinutesToday: Int = 0,
    val remainingMinutesToday: Int = 120,
    val isDowntimeActive: Boolean = false
)

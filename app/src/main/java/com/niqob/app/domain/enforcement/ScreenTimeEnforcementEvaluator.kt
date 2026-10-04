package com.niqob.app.domain.enforcement

import com.niqob.app.domain.calculator.DailyScreenTimeCalculator
import com.niqob.app.domain.model.DailyScreenTimeStatus
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus

/**
 * Pure domain evaluator that calculates screen-time enforcement states
 * without relying on any Android framework or UI APIs.
 *
 * Rules:
 * 1. If screen-time limit is disabled -> enforcement must be inactive (ScreenTimeEnforcementStatus.DISABLED).
 * 2. If enforcement is enabled and usage is below limit -> not blocked (ScreenTimeEnforcementStatus.WITHIN_LIMIT).
 * 3. If enforcement is enabled and usage reaches or exceeds limit -> enforcement active (ScreenTimeEnforcementStatus.LIMIT_REACHED).
 * 4. Zero limit is handled safely without division by zero.
 * 5. Uses DailyScreenTimeCalculator to ensure zero calculation duplication across the architecture.
 */
object ScreenTimeEnforcementEvaluator {

    /**
     * Evaluates enforcement state from raw metrics by delegating to DailyScreenTimeCalculator.
     */
    fun evaluate(
        usedMinutes: Int,
        dailyLimitMinutes: Int,
        isEnabled: Boolean,
        date: String = ""
    ): ScreenTimeEnforcementState {
        val dailyStatus = DailyScreenTimeCalculator.calculate(
            usedMinutes = usedMinutes,
            dailyLimitMinutes = dailyLimitMinutes,
            isEnabled = isEnabled,
            date = date
        )
        return evaluate(dailyStatus)
    }

    /**
     * Evaluates enforcement state directly from an existing DailyScreenTimeStatus.
     */
    fun evaluate(dailyStatus: DailyScreenTimeStatus): ScreenTimeEnforcementState {
        return ScreenTimeEnforcementState.fromDailyScreenTimeStatus(dailyStatus)
    }
}

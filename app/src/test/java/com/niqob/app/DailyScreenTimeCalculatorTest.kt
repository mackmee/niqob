package com.niqob.app

import com.niqob.app.domain.calculator.DailyScreenTimeCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyScreenTimeCalculatorTest {

    @Test
    fun calculate_zeroUsageWith60Limit_isWithinLimit() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 0,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals("2026-10-03", status.date)
        assertEquals(0, status.usedMinutes)
        assertEquals(60, status.dailyLimitMinutes)
        assertEquals(60, status.remainingMinutes)
        assertEquals(0, status.usagePercentage)
        assertFalse(status.isLimitReached)
        assertTrue(status.isWithinLimit)
        assertTrue(status.isEnabled)
        assertEquals("Within limit", status.statusText)
    }

    @Test
    fun calculate_thirtyUsageWith60Limit_calculatesHalfwayAccurately() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 30,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals(30, status.usedMinutes)
        assertEquals(60, status.dailyLimitMinutes)
        assertEquals(30, status.remainingMinutes)
        assertEquals(50, status.usagePercentage)
        assertFalse(status.isLimitReached)
        assertEquals("Within limit", status.statusText)
    }

    @Test
    fun calculate_sixtyUsageWith60Limit_reachesLimitExact() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 60,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals(60, status.usedMinutes)
        assertEquals(60, status.dailyLimitMinutes)
        assertEquals(0, status.remainingMinutes)
        assertEquals(100, status.usagePercentage)
        assertTrue(status.isLimitReached)
        assertFalse(status.isWithinLimit)
        assertEquals("Limit reached", status.statusText)
    }

    @Test
    fun calculate_ninetyUsageWith60Limit_remainingNeverNegativeAndLimitReached() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 90,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals(90, status.usedMinutes)
        assertEquals(60, status.dailyLimitMinutes)
        assertEquals(0, status.remainingMinutes)
        assertEquals(150, status.usagePercentage)
        assertTrue(status.isLimitReached)
        assertEquals("Limit reached", status.statusText)
    }

    @Test
    fun calculate_zeroUsageWithZeroLimit_enabledReachesLimitSafely() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 0,
            dailyLimitMinutes = 0,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals(0, status.usedMinutes)
        assertEquals(0, status.dailyLimitMinutes)
        assertEquals(0, status.remainingMinutes)
        assertEquals(100, status.usagePercentage)
        assertTrue(status.isLimitReached)
        assertEquals("Limit reached", status.statusText)
    }

    @Test
    fun calculate_disabledLimit_neverMarksLimitReached() {
        // Under limit when disabled
        val underStatus = DailyScreenTimeCalculator.calculate(
            usedMinutes = 30,
            dailyLimitMinutes = 60,
            isEnabled = false,
            date = "2026-10-03"
        )
        assertFalse(underStatus.isLimitReached)
        assertFalse(underStatus.isEnabled)
        assertEquals(30, underStatus.remainingMinutes)
        assertEquals("Screen Time limit disabled", underStatus.statusText)

        // Over limit when disabled
        val overStatus = DailyScreenTimeCalculator.calculate(
            usedMinutes = 90,
            dailyLimitMinutes = 60,
            isEnabled = false,
            date = "2026-10-03"
        )
        assertFalse(overStatus.isLimitReached)
        assertFalse(overStatus.isEnabled)
        assertEquals(0, overStatus.remainingMinutes)
        assertEquals(150, overStatus.usagePercentage)
        assertEquals("Screen Time limit disabled", overStatus.statusText)
    }

    @Test
    fun calculate_1440UsageWith1440Limit_handlesMaxDayLimit() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 1440,
            dailyLimitMinutes = 1440,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals(1440, status.usedMinutes)
        assertEquals(1440, status.dailyLimitMinutes)
        assertEquals(0, status.remainingMinutes)
        assertEquals(100, status.usagePercentage)
        assertTrue(status.isLimitReached)
        assertEquals("Limit reached", status.statusText)
    }

    @Test
    fun calculate_usageAbove1440Defensively_isHandledGracefully() {
        val status = DailyScreenTimeCalculator.calculate(
            usedMinutes = 1500,
            dailyLimitMinutes = 1440,
            isEnabled = true,
            date = "2026-10-03"
        )

        assertEquals(1500, status.usedMinutes)
        assertEquals(1440, status.dailyLimitMinutes)
        assertEquals(0, status.remainingMinutes)
        assertEquals(104, status.usagePercentage)
        assertTrue(status.isLimitReached)
    }

    @Test
    fun calculate_percentageBoundaryBehavior_handlesRoundingAndZeroLimit() {
        // Prompt specific example: 37 min used of 60 min limit -> 62%
        val status37 = DailyScreenTimeCalculator.calculate(
            usedMinutes = 37,
            dailyLimitMinutes = 60,
            isEnabled = true
        )
        assertEquals(23, status37.remainingMinutes)
        assertEquals(62, status37.usagePercentage)
        assertEquals("Within limit", status37.statusText)

        // 1 min of 60 min -> 2%
        val status1 = DailyScreenTimeCalculator.calculate(
            usedMinutes = 1,
            dailyLimitMinutes = 60,
            isEnabled = true
        )
        assertEquals(2, status1.usagePercentage)

        // Negative input defensively treated as 0
        val statusNeg = DailyScreenTimeCalculator.calculate(
            usedMinutes = -15,
            dailyLimitMinutes = 60,
            isEnabled = true
        )
        assertEquals(0, statusNeg.usedMinutes)
        assertEquals(60, statusNeg.remainingMinutes)
        assertEquals(0, statusNeg.usagePercentage)

        // Zero limit disabled -> 0%
        val statusZeroDisabled = DailyScreenTimeCalculator.calculate(
            usedMinutes = 0,
            dailyLimitMinutes = 0,
            isEnabled = false
        )
        assertEquals(0, statusZeroDisabled.usagePercentage)
    }

    @Test
    fun calculate_remainingTimeNeverNegative_underExtremeExcess() {
        val statusExcess = DailyScreenTimeCalculator.calculate(
            usedMinutes = 99999,
            dailyLimitMinutes = 60,
            isEnabled = true
        )
        assertEquals(0, statusExcess.remainingMinutes)
        assertTrue(statusExcess.isLimitReached)

        val statusExcessZeroLimit = DailyScreenTimeCalculator.calculate(
            usedMinutes = 100,
            dailyLimitMinutes = 0,
            isEnabled = true
        )
        assertEquals(0, statusExcessZeroLimit.remainingMinutes)
        assertTrue(statusExcessZeroLimit.isLimitReached)
    }
}

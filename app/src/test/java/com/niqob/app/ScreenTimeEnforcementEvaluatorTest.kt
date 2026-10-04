package com.niqob.app

import com.niqob.app.domain.calculator.DailyScreenTimeCalculator
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.model.DailyScreenTimeStatus
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.fakes.FakeScreenTimeEnforcementRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeEnforcementEvaluatorTest {

    @Test
    fun evaluate_whenDisabledLimit_returnsDisabledEnforcement() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 150,
            dailyLimitMinutes = 120,
            isEnabled = false,
            date = "2026-10-04"
        )

        assertFalse(state.isEnforcementEnabled)
        assertFalse(state.isEnforcementActive)
        assertEquals(ScreenTimeEnforcementStatus.DISABLED, state.status)
        assertEquals("Enforcement disabled", state.status.displayName)
        assertEquals(150, state.usedMinutes)
        assertEquals(120, state.dailyLimitMinutes)
        assertEquals(0, state.remainingMinutes)
        assertEquals(125, state.usagePercentage)
        assertEquals("2026-10-04", state.usageDate)
    }

    @Test
    fun evaluate_whenUsageBelowLimit_returnsWithinLimit() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 45,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )

        assertTrue(state.isEnforcementEnabled)
        assertFalse(state.isEnforcementActive)
        assertTrue(state.isWithinLimit)
        assertFalse(state.isLimitReached)
        assertEquals(ScreenTimeEnforcementStatus.WITHIN_LIMIT, state.status)
        assertEquals("Within limit", state.status.displayName)
        assertEquals(45, state.usedMinutes)
        assertEquals(120, state.dailyLimitMinutes)
        assertEquals(75, state.remainingMinutes)
        assertEquals(38, state.usagePercentage)
        assertEquals("2026-10-04", state.usageDate)
    }

    @Test
    fun evaluate_whenUsageExactlyAtLimit_returnsLimitReachedAndEnforcementActive() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 120,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )

        assertTrue(state.isEnforcementEnabled)
        assertTrue(state.isEnforcementActive)
        assertFalse(state.isWithinLimit)
        assertTrue(state.isLimitReached)
        assertEquals(ScreenTimeEnforcementStatus.LIMIT_REACHED, state.status)
        assertEquals("Limit reached", state.status.displayName)
        assertEquals(120, state.usedMinutes)
        assertEquals(120, state.dailyLimitMinutes)
        assertEquals(0, state.remainingMinutes)
        assertEquals(100, state.usagePercentage)
    }

    @Test
    fun evaluate_whenUsageAboveLimit_returnsLimitReachedAndEnforcementActive() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 135,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )

        assertTrue(state.isEnforcementEnabled)
        assertTrue(state.isEnforcementActive)
        assertFalse(state.isWithinLimit)
        assertTrue(state.isLimitReached)
        assertEquals(ScreenTimeEnforcementStatus.LIMIT_REACHED, state.status)
        assertEquals("Limit reached", state.status.displayName)
        assertEquals(135, state.usedMinutes)
        assertEquals(120, state.dailyLimitMinutes)
        assertEquals(0, state.remainingMinutes)
        assertEquals(113, state.usagePercentage)
    }

    @Test
    fun evaluate_whenZeroUsage_returnsWithinLimit() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 0,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )

        assertTrue(state.isEnforcementEnabled)
        assertFalse(state.isEnforcementActive)
        assertTrue(state.isWithinLimit)
        assertFalse(state.isLimitReached)
        assertEquals(ScreenTimeEnforcementStatus.WITHIN_LIMIT, state.status)
        assertEquals(0, state.usedMinutes)
        assertEquals(60, state.dailyLimitMinutes)
        assertEquals(60, state.remainingMinutes)
        assertEquals(0, state.usagePercentage)
    }

    @Test
    fun evaluate_whenZeroMinuteLimit_handlesSafelyWithoutDivisionByZero() {
        // Zero limit and zero usage, enabled
        val stateZeroUsage = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 0,
            dailyLimitMinutes = 0,
            isEnabled = true,
            date = "2026-10-04"
        )
        assertTrue(stateZeroUsage.isEnforcementEnabled)
        assertTrue(stateZeroUsage.isLimitReached)
        assertTrue(stateZeroUsage.isEnforcementActive)
        assertEquals(0, stateZeroUsage.remainingMinutes)
        assertEquals(100, stateZeroUsage.usagePercentage)
        assertEquals(ScreenTimeEnforcementStatus.LIMIT_REACHED, stateZeroUsage.status)

        // Zero limit and some usage
        val stateSomeUsage = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 15,
            dailyLimitMinutes = 0,
            isEnabled = true,
            date = "2026-10-04"
        )
        assertTrue(stateSomeUsage.isEnforcementEnabled)
        assertTrue(stateSomeUsage.isLimitReached)
        assertTrue(stateSomeUsage.isEnforcementActive)
        assertEquals(0, stateSomeUsage.remainingMinutes)
        assertEquals(100, stateSomeUsage.usagePercentage)
        assertEquals(ScreenTimeEnforcementStatus.LIMIT_REACHED, stateSomeUsage.status)

        // Zero limit but disabled
        val stateDisabled = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 0,
            dailyLimitMinutes = 0,
            isEnabled = false,
            date = "2026-10-04"
        )
        assertFalse(stateDisabled.isEnforcementEnabled)
        assertFalse(stateDisabled.isEnforcementActive)
        assertEquals(0, stateDisabled.usagePercentage)
        assertEquals(ScreenTimeEnforcementStatus.DISABLED, stateDisabled.status)
    }

    @Test
    fun evaluate_whenLargeUsageValue_calculatesSafely() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 2000,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )

        assertTrue(state.isEnforcementEnabled)
        assertTrue(state.isEnforcementActive)
        assertTrue(state.isLimitReached)
        assertEquals(ScreenTimeEnforcementStatus.LIMIT_REACHED, state.status)
        assertEquals(2000, state.usedMinutes)
        assertEquals(60, state.dailyLimitMinutes)
        assertEquals(0, state.remainingMinutes)
        assertEquals(3333, state.usagePercentage) // 2000/60 = 3333%
    }

    @Test
    fun fromDailyScreenTimeStatus_mapsCorrectlyWithoutDuplication() {
        val dailyStatus = DailyScreenTimeCalculator.calculate(
            usedMinutes = 75,
            dailyLimitMinutes = 90,
            isEnabled = true,
            date = "2026-10-04"
        )

        val state = ScreenTimeEnforcementState.fromDailyScreenTimeStatus(dailyStatus)

        assertEquals(dailyStatus.isEnabled, state.isEnforcementEnabled)
        assertEquals(dailyStatus.isLimitReached, state.isLimitReached)
        assertEquals(dailyStatus.usedMinutes, state.usedMinutes)
        assertEquals(dailyStatus.dailyLimitMinutes, state.dailyLimitMinutes)
        assertEquals(dailyStatus.remainingMinutes, state.remainingMinutes)
        assertEquals(dailyStatus.usagePercentage, state.usagePercentage)
        assertEquals(dailyStatus.date, state.usageDate)
        assertEquals(ScreenTimeEnforcementStatus.WITHIN_LIMIT, state.status)
        assertFalse(state.isEnforcementActive)

        // Same check with evaluator
        val evaluatedState = ScreenTimeEnforcementEvaluator.evaluate(dailyStatus)
        assertEquals(state, evaluatedState)
    }

    @Test
    fun noDuplicateCalculationLogic_evaluatorDelegatesExactResults() {
        val testCases = listOf(
            Triple(0, 120, true),
            Triple(60, 120, true),
            Triple(120, 120, true),
            Triple(180, 120, true),
            Triple(50, 100, false),
            Triple(0, 0, true),
            Triple(10, 0, true)
        )

        for ((used, limit, enabled) in testCases) {
            val calcResult = DailyScreenTimeCalculator.calculate(
                usedMinutes = used,
                dailyLimitMinutes = limit,
                isEnabled = enabled,
                date = "2026-10-04"
            )
            val evalResult = ScreenTimeEnforcementEvaluator.evaluate(
                usedMinutes = used,
                dailyLimitMinutes = limit,
                isEnabled = enabled,
                date = "2026-10-04"
            )

            assertEquals(calcResult.usedMinutes, evalResult.usedMinutes)
            assertEquals(calcResult.dailyLimitMinutes, evalResult.dailyLimitMinutes)
            assertEquals(calcResult.remainingMinutes, evalResult.remainingMinutes)
            assertEquals(calcResult.usagePercentage, evalResult.usagePercentage)
            assertEquals(calcResult.isLimitReached, evalResult.isLimitReached)
            assertEquals(calcResult.isEnabled, evalResult.isEnforcementEnabled)
            assertEquals(calcResult.date, evalResult.usageDate)
        }
    }

    @Test
    fun localEnforcementRepository_storesAndEmitsStateCorrectly() {
        val repo = LocalScreenTimeEnforcementRepository.getInstance()
        LocalScreenTimeEnforcementRepository.resetForTesting()
        val freshRepo = LocalScreenTimeEnforcementRepository.getInstance()

        assertNull(freshRepo.getEnforcementState().value)

        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 50,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        freshRepo.updateEnforcementState(state)

        assertEquals(state, freshRepo.getEnforcementState().value)

        freshRepo.clear()
        assertNull(freshRepo.getEnforcementState().value)
    }

    @Test
    fun fakeEnforcementRepository_recordsUpdates() {
        val fakeRepo = FakeScreenTimeEnforcementRepository()
        assertNull(fakeRepo.getEnforcementState().value)
        assertEquals(0, fakeRepo.updateCount)

        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 80,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepo.updateEnforcementState(state)

        assertEquals(state, fakeRepo.getEnforcementState().value)
        assertEquals(1, fakeRepo.updateCount)
        assertTrue(fakeRepo.getEnforcementState().value!!.isEnforcementActive)

        fakeRepo.clear()
        assertNull(fakeRepo.getEnforcementState().value)
    }
}

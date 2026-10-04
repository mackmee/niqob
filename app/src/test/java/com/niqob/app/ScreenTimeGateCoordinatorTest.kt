package com.niqob.app

import com.niqob.app.domain.calculator.DailyScreenTimeCalculator
import com.niqob.app.domain.enforcement.GateDecision
import com.niqob.app.domain.enforcement.GateNavigationAction
import com.niqob.app.domain.enforcement.ScreenTimeEnforcementEvaluator
import com.niqob.app.domain.enforcement.ScreenTimeGateCoordinator
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import com.niqob.app.fakes.FakeScreenTimeEnforcementRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenTimeGateCoordinatorTest {

    private lateinit var fakeRepository: FakeScreenTimeEnforcementRepository
    private lateinit var coordinator: ScreenTimeGateCoordinator

    @Before
    fun setUp() {
        fakeRepository = FakeScreenTimeEnforcementRepository()
        coordinator = ScreenTimeGateCoordinator(fakeRepository)
    }

    @Test
    fun initialState_whenNoStateInRepository_allowsNormalChildExperience() {
        val decision = coordinator.evaluateGate()
        assertEquals(GateDecision.AllowNormalChildExperience, decision)
        assertFalse(coordinator.shouldShowLimitReached())
        assertEquals(
            GateNavigationAction.NoAction,
            coordinator.resolveNavigationAction(currentRoute = "child_home", isChildRoute = true)
        )
    }

    @Test
    fun evaluateGate_whenDisabledLimit_allowsNormalChildExperience() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 150,
            dailyLimitMinutes = 120,
            isEnabled = false,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(state)

        val decision = coordinator.evaluateGate()
        assertEquals(GateDecision.AllowNormalChildExperience, decision)
        assertFalse(coordinator.shouldShowLimitReached())

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "child_home",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.NoAction, navAction)
    }

    @Test
    fun evaluateGate_whenWithinLimit_allowsNormalChildExperience() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 45,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(state)

        val decision = coordinator.evaluateGate()
        assertEquals(GateDecision.AllowNormalChildExperience, decision)
        assertFalse(coordinator.shouldShowLimitReached())

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "child_home",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.NoAction, navAction)
    }

    @Test
    fun evaluateGate_whenLimitReached_returnsShowLimitReachedAndNavigatesToGate() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 120,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(state)

        val decision = coordinator.evaluateGate()
        assertTrue(decision is GateDecision.ShowLimitReached)
        val limitReachedDecision = decision as GateDecision.ShowLimitReached
        assertEquals(120, limitReachedDecision.state.usedMinutes)
        assertEquals(120, limitReachedDecision.state.dailyLimitMinutes)
        assertEquals(0, limitReachedDecision.state.remainingMinutes)
        assertTrue(coordinator.shouldShowLimitReached())

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "child_home",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.NavigateToGate("screen_time_limit_reached"), navAction)
    }

    @Test
    fun resolveNavigationAction_whenLimitReachedAndAlreadyOnGateScreen_returnsNoActionToAvoidLoop() {
        val state = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 135,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(state)

        // Already on the gate screen
        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "screen_time_limit_reached",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.NoAction, navAction)
    }

    @Test
    fun resolveNavigationAction_whenStateChangesFromLimitReachedToWithinLimit_returnsDismissGate() {
        // Transition: Child is currently on gate screen, but usage drops or limit increases
        val stateWithinLimit = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 50,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(stateWithinLimit)

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "screen_time_limit_reached",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.DismissGate, navAction)
    }

    @Test
    fun resolveNavigationAction_whenStateChangesFromLimitReachedToDisabled_returnsDismissGate() {
        val stateDisabled = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 130,
            dailyLimitMinutes = 120,
            isEnabled = false,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(stateDisabled)

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "screen_time_limit_reached",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.DismissGate, navAction)
    }

    @Test
    fun resolveNavigationAction_whenNonChildRoute_neverNavigatesToGate() {
        // Even when limit is reached, parent screens must not be hijacked
        val stateLimitReached = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 120,
            dailyLimitMinutes = 120,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(stateLimitReached)

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "parent_dashboard",
            isChildRoute = false
        )
        assertEquals(GateNavigationAction.NoAction, navAction)
    }

    @Test
    fun evaluateGate_whenZeroMinuteLimitEnabled_triggersGateImmediately() {
        val stateZero = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 0,
            dailyLimitMinutes = 0,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(stateZero)

        assertTrue(coordinator.shouldShowLimitReached())
        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "child_learning",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.NavigateToGate("screen_time_limit_reached"), navAction)
    }

    @Test
    fun evaluateGate_whenLargeUsageValue_triggersGateCorrectly() {
        val stateLarge = ScreenTimeEnforcementEvaluator.evaluate(
            usedMinutes = 3000,
            dailyLimitMinutes = 60,
            isEnabled = true,
            date = "2026-10-04"
        )
        fakeRepository.updateEnforcementState(stateLarge)

        assertTrue(coordinator.shouldShowLimitReached())
        val decision = coordinator.evaluateGate()
        assertTrue(decision is GateDecision.ShowLimitReached)
        val limitReached = decision as GateDecision.ShowLimitReached
        assertEquals(3000, limitReached.state.usedMinutes)
        assertEquals(60, limitReached.state.dailyLimitMinutes)
        assertEquals(0, limitReached.state.remainingMinutes)

        val navAction = coordinator.resolveNavigationAction(
            currentRoute = "child_ai",
            isChildRoute = true
        )
        assertEquals(GateNavigationAction.NavigateToGate("screen_time_limit_reached"), navAction)
    }

    @Test
    fun gateCoordinator_dynamicallyTracksRepositoryEmissions() {
        // Step 1: Initial state is within limit
        fakeRepository.updateEnforcementState(
            ScreenTimeEnforcementEvaluator.evaluate(30, 60, true, "2026-10-04")
        )
        assertFalse(coordinator.shouldShowLimitReached())
        assertEquals(
            GateNavigationAction.NoAction,
            coordinator.resolveNavigationAction("child_home", isChildRoute = true)
        )

        // Step 2: Usage reaches limit
        fakeRepository.updateEnforcementState(
            ScreenTimeEnforcementEvaluator.evaluate(60, 60, true, "2026-10-04")
        )
        assertTrue(coordinator.shouldShowLimitReached())
        assertEquals(
            GateNavigationAction.NavigateToGate("screen_time_limit_reached"),
            coordinator.resolveNavigationAction("child_home", isChildRoute = true)
        )

        // Step 3: Already navigated to gate -> loop avoided
        assertEquals(
            GateNavigationAction.NoAction,
            coordinator.resolveNavigationAction("screen_time_limit_reached", isChildRoute = true)
        )

        // Step 4: Parent increases limit or disables -> gate dismissed
        fakeRepository.updateEnforcementState(
            ScreenTimeEnforcementEvaluator.evaluate(60, 120, true, "2026-10-04")
        )
        assertFalse(coordinator.shouldShowLimitReached())
        assertEquals(
            GateNavigationAction.DismissGate,
            coordinator.resolveNavigationAction("screen_time_limit_reached", isChildRoute = true)
        )
    }
}

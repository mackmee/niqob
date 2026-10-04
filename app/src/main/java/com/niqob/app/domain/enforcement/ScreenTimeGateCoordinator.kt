package com.niqob.app.domain.enforcement

import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import com.niqob.app.domain.repository.ScreenTimeEnforcementRepository
import kotlinx.coroutines.flow.StateFlow

sealed class GateDecision {
    data object AllowNormalChildExperience : GateDecision()
    data class ShowLimitReached(val state: ScreenTimeEnforcementState) : GateDecision()
}

sealed class GateNavigationAction {
    data class NavigateToGate(val route: String) : GateNavigationAction()
    data object DismissGate : GateNavigationAction()
    data object NoAction : GateNavigationAction()
}

/**
 * Pure domain gate coordinator that determines whether the Child app
 * should display the dedicated ScreenTimeLimitReachedScreen or allow
 * the normal Child experience.
 *
 * Rules:
 * - DISABLED: normal Child experience (NoAction)
 * - WITHIN_LIMIT: normal Child experience (NoAction)
 * - LIMIT_REACHED: navigate to ScreenTimeLimitReachedScreen
 *
 * Loop avoidance:
 * - If already on the gate screen, do NOT repeatedly navigate.
 * - If limit is reset or disabled while on gate screen, trigger DismissGate.
 */
class ScreenTimeGateCoordinator(
    private val enforcementRepository: ScreenTimeEnforcementRepository
) {
    val enforcementState: StateFlow<ScreenTimeEnforcementState?>
        get() = enforcementRepository.getEnforcementState()

    fun evaluateGate(state: ScreenTimeEnforcementState? = enforcementState.value): GateDecision {
        if (state == null) {
            return GateDecision.AllowNormalChildExperience
        }

        return when (state.status) {
            ScreenTimeEnforcementStatus.DISABLED -> GateDecision.AllowNormalChildExperience
            ScreenTimeEnforcementStatus.WITHIN_LIMIT -> GateDecision.AllowNormalChildExperience
            ScreenTimeEnforcementStatus.LIMIT_REACHED -> {
                if (state.isEnforcementActive) {
                    GateDecision.ShowLimitReached(state)
                } else {
                    GateDecision.AllowNormalChildExperience
                }
            }
        }
    }

    fun shouldShowLimitReached(state: ScreenTimeEnforcementState? = enforcementState.value): Boolean {
        return evaluateGate(state) is GateDecision.ShowLimitReached
    }

    /**
     * Resolves the navigation action based on the current route and enforcement state.
     * Prevents navigation loops when already on the gate destination.
     *
     * @param currentRoute The active navigation route.
     * @param isChildRoute Whether the current route belongs to the Child section.
     * @param state The current enforcement state.
     * @param targetRoute The route of ScreenTimeLimitReachedScreen.
     */
    fun resolveNavigationAction(
        currentRoute: String?,
        isChildRoute: Boolean,
        state: ScreenTimeEnforcementState? = enforcementState.value,
        targetRoute: String = "screen_time_limit_reached"
    ): GateNavigationAction {
        if (!isChildRoute) {
            return GateNavigationAction.NoAction
        }

        val shouldShow = shouldShowLimitReached(state)
        val isOnGateScreen = currentRoute == targetRoute

        return when {
            // Case 1: Limit reached, but not yet on gate screen -> Navigate to gate
            shouldShow && !isOnGateScreen -> GateNavigationAction.NavigateToGate(targetRoute)

            // Case 2: Limit reached and already on gate screen -> Avoid loop
            shouldShow && isOnGateScreen -> GateNavigationAction.NoAction

            // Case 3: Limit NOT reached (or disabled), but currently on gate screen -> Dismiss gate
            !shouldShow && isOnGateScreen -> GateNavigationAction.DismissGate

            // Case 4: Normal state on normal screen -> No action needed
            else -> GateNavigationAction.NoAction
        }
    }
}

package com.niqob.app

import com.niqob.app.core.navigation.Screen
import com.niqob.app.domain.model.UserMode
import com.niqob.app.feature.child.ChildHomeViewModel
import com.niqob.app.feature.child.ChildPairingViewModel
import com.niqob.app.feature.onboarding.WelcomeViewModel
import com.niqob.app.feature.pairing.ParentPairingViewModel
import com.niqob.app.feature.parent.ParentDashboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationAndViewModelTest {

    @Test
    fun screenRoutes_areUniqueAndCorrect() {
        val routes = listOf(
            Screen.Welcome.route,
            Screen.Login.route,
            Screen.Register.route,
            Screen.ParentDashboard.route,
            Screen.ChildHome.route,
            Screen.DevicePairing.route,
            Screen.ManageChild.route,
            Screen.AppPermissions.route,
            Screen.ScreenTime.route,
            Screen.AiSettings.route,
            Screen.SafetyMonitoring.route,
            Screen.Settings.route,
            Screen.ChildPairing.route,
            Screen.ChildLearning.route,
            Screen.ChildAi.route,
            Screen.ChildAllowedApps.route,
            Screen.ChildGoals.route,
            Screen.UsageAccessVerification.route,
            Screen.ScreenTimeLimitReached.route
        )

        assertEquals("Routes count", 19, routes.size)
        assertEquals("Unique routes count", 19, routes.distinct().size)

    }

    @Test
    fun parentDashboardViewModel_initialStateMatchesRequirements() {
        val viewModel = ParentDashboardViewModel()
        val state = viewModel.uiState.value

        assertFalse(state.childStatus.isConnected)
        assertEquals("Not connected", state.childStatus.deviceName)
        assertEquals(0, state.childStatus.screenTimeMinutes)
        assertEquals(0, state.childStatus.allowedAppsCount)
        assertEquals(0, state.childStatus.safetyAlertsCount)
    }

    @Test
    fun childHomeViewModel_initialStateMatchesRequirements() {
        val viewModel = ChildHomeViewModel()
        val state = viewModel.uiState.value

        assertEquals("Welcome to your safe digital space.", state.greeting)
        assertNotNull(state.dailyGoalProgress)
        assertEquals(2, state.completedActivities)
        assertFalse(state.isParentConnected)
        assertNull(state.parentDeviceId)
    }

    @Test
    fun parentPairingViewModel_initialStateIsClean() {
        val viewModel = ParentPairingViewModel()
        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun childPairingViewModel_filtersInputToSixDigits() {
        val viewModel = ChildPairingViewModel()

        viewModel.onCodeChanged("12a3b45678")
        assertEquals("123456", viewModel.uiState.value.codeInput)

        viewModel.onCodeChanged("987")
        assertEquals("987", viewModel.uiState.value.codeInput)
    }

    @Test
    fun supabasePairingRepository_singleton_returnsSameInstance() {
        val instance1 = com.niqob.app.data.repository.SupabasePairingRepository.getInstance()
        val instance2 = com.niqob.app.data.repository.SupabasePairingRepository.getInstance()
        org.junit.Assert.assertSame(instance1, instance2)
    }
}

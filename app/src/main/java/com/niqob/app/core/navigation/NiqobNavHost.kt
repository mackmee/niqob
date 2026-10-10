package com.niqob.app.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.data.repository.SupabaseAuthRepository
import com.niqob.app.domain.enforcement.GateNavigationAction
import com.niqob.app.domain.enforcement.ScreenTimeGateCoordinator
import com.niqob.app.feature.ai.AiTutorScreen
import com.niqob.app.feature.auth.LoginScreen
import com.niqob.app.feature.auth.RegisterScreen
import com.niqob.app.feature.child.ChildHomeScreen
import com.niqob.app.feature.child.ChildPairingScreen
import com.niqob.app.feature.child.ChildSectionPlaceholderScreen
import com.niqob.app.feature.child.enforcement.ScreenTimeLimitReachedScreen
import com.niqob.app.feature.child.usage.UsageAccessScreen
import com.niqob.app.feature.onboarding.WelcomeScreen
import com.niqob.app.feature.pairing.DevicePairingScreen
import com.niqob.app.feature.child.override.RemoteOverrideRequestScreen
import com.niqob.app.feature.parent.ManageChildScreen
import com.niqob.app.feature.parent.ParentDashboardScreen
import com.niqob.app.feature.parent.override.ParentRemoteOverrideScreen
import com.niqob.app.feature.permissions.AppPermissionsScreen
import com.niqob.app.feature.safety.SafetyMonitoringScreen
import com.niqob.app.feature.screentime.ScreenTimeScreen
import com.niqob.app.feature.settings.SettingsScreen

@Composable
fun NiqobNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Welcome.route,
    gateCoordinator: ScreenTimeGateCoordinator = remember {
        ScreenTimeGateCoordinator(LocalScreenTimeEnforcementRepository.getInstance())
    }
) {
    val enforcementState by gateCoordinator.enforcementState.collectAsState()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val isChildRoute = currentRoute in listOf(
        Screen.ChildHome.route,
        Screen.ChildPairing.route,
        Screen.ChildLearning.route,
        Screen.ChildAi.route,
        Screen.ChildAllowedApps.route,
        Screen.ChildGoals.route,
        Screen.UsageAccessVerification.route,
        Screen.ScreenTimeLimitReached.route
    )

    val navigateToGateEvent by com.niqob.app.MainActivity.navigateToLimitGateEvent.collectAsState()

    LaunchedEffect(navigateToGateEvent) {
        if (navigateToGateEvent != null) {
            com.niqob.app.MainActivity.clearNavigationEvent()
            if (currentRoute != Screen.ScreenTimeLimitReached.route) {
                navController.navigate(Screen.ScreenTimeLimitReached.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(enforcementState, currentRoute, isChildRoute) {
        val action = gateCoordinator.resolveNavigationAction(currentRoute, isChildRoute, enforcementState)
        android.util.Log.d("ScreenTimeGate", "Gate LaunchedEffect: route=$currentRoute, isChild=$isChildRoute, state=$enforcementState -> action=$action")
        when (action) {
            is GateNavigationAction.NavigateToGate -> {
                android.util.Log.d("ScreenTimeGate", "Navigating to gate: ${action.route}")
                navController.navigate(action.route) {
                    launchSingleTop = true
                }
            }
            is GateNavigationAction.DismissGate -> {
                android.util.Log.d("ScreenTimeGate", "Dismissing gate from backstack")
                if (!navController.popBackStack()) {
                    android.util.Log.d("ScreenTimeGate", "popBackStack returned false, navigating to ChildHome")
                    navController.navigate(Screen.ChildHome.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = false }
                    }
                }
            }
            is GateNavigationAction.NoAction -> {
                android.util.Log.d("ScreenTimeGate", "NoAction taken")
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Welcome Screen
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onSelectParent = {
                    val authRepo = SupabaseAuthRepository()
                    if (authRepo.getCurrentUser() != null) {
                        navController.navigate(Screen.ParentDashboard.route)
                    } else {
                        navController.navigate(Screen.Login.route)
                    }
                },

                onSelectChild = {
                    if (gateCoordinator.shouldShowLimitReached()) {
                        navController.navigate(Screen.ScreenTimeLimitReached.route)
                    } else {
                        navController.navigate(Screen.ChildHome.route)
                    }
                }
            )
        }

        // Parent Auth Screens
        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onLoginSuccess = {
                    navController.navigate(Screen.ParentDashboard.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = false }
                    }
                }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLogin = { navController.popBackStack() },
                onRegisterSuccess = {
                    navController.navigate(Screen.ParentDashboard.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = false }
                    }
                }
            )
        }

        // Parent Dashboard
        composable(Screen.ParentDashboard.route) {
            ParentDashboardScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPairing = { navController.navigate(Screen.DevicePairing.route) },
                onNavigateToManageChild = { navController.navigate(Screen.ManageChild.route) },
                onNavigateToPermissions = { navController.navigate(Screen.AppPermissions.route) },
                onNavigateToScreenTime = { navController.navigate(Screen.ScreenTime.route) },
                onNavigateToAiSettings = { navController.navigate(Screen.AiSettings.route) },
                onNavigateToSafety = { navController.navigate(Screen.SafetyMonitoring.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToScreenTimeRequests = { navController.navigate(Screen.ParentRemoteOverride.route) },
                onLogout = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Child Home
        composable(Screen.ChildHome.route) {
            ChildHomeScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToConnectParent = { navController.navigate(Screen.ChildPairing.route) },
                onNavigateToLearning = { navController.navigate(Screen.ChildLearning.route) },
                onNavigateToAi = { navController.navigate(Screen.ChildAi.route) },
                onNavigateToAllowedApps = { navController.navigate(Screen.ChildAllowedApps.route) },
                onNavigateToGoals = { navController.navigate(Screen.ChildGoals.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToUsageVerification = { navController.navigate(Screen.UsageAccessVerification.route) }
            )
        }

        // Parent Feature Placeholders & Flows
        composable(Screen.DevicePairing.route) {
            DevicePairingScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.ManageChild.route) {
            ManageChildScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.AppPermissions.route) {
            AppPermissionsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToUsageVerification = { navController.navigate(Screen.UsageAccessVerification.route) }
            )
        }
        composable(Screen.ScreenTime.route) {
            ScreenTimeScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.AiSettings.route) {
            AiTutorScreen(
                title = "AI Settings",
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.SafetyMonitoring.route) {
            SafetyMonitoringScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Child Feature Placeholders & Flows
        composable(Screen.ChildPairing.route) {
            ChildPairingScreen(
                onNavigateBack = { navController.popBackStack() },
                onPairingSuccess = { navController.popBackStack() }
            )
        }
        composable(Screen.ChildLearning.route) {
            ChildSectionPlaceholderScreen(
                title = "Learning Quests",
                description = "Interactive learning paths, quizzes, and digital discovery curated for your age level.",
                icon = Icons.Default.School,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ChildAi.route) {
            AiTutorScreen(
                title = "NIQOB AI Tutor",
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ChildAllowedApps.route) {
            ChildSectionPlaceholderScreen(
                title = "Allowed Apps",
                description = "Safe applications approved by your parents. Launch and enjoy without digital distractions.",
                icon = Icons.Default.Apps,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ChildGoals.route) {
            ChildSectionPlaceholderScreen(
                title = "My Goals",
                description = "Celebrate daily streaks, finish reading challenges, and earn reward stars.",
                icon = Icons.Default.EmojiEvents,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.UsageAccessVerification.route) {
            UsageAccessScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.ScreenTimeLimitReached.route) {
            ScreenTimeLimitReachedScreen(
                onNavigateBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Welcome.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onRequestRemoteOverride = {
                    navController.navigate(Screen.RemoteOverrideRequest.route)
                }
            )
        }
        composable(Screen.RemoteOverrideRequest.route) {
            RemoteOverrideRequestScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ParentRemoteOverride.route) {
            ParentRemoteOverrideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

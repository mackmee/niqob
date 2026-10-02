package com.niqob.app.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.School
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.niqob.app.data.repository.SupabaseAuthRepository
import com.niqob.app.feature.ai.AiTutorScreen
import com.niqob.app.feature.auth.LoginScreen
import com.niqob.app.feature.auth.RegisterScreen
import com.niqob.app.feature.child.ChildHomeScreen
import com.niqob.app.feature.child.ChildPairingScreen
import com.niqob.app.feature.child.ChildSectionPlaceholderScreen
import com.niqob.app.feature.onboarding.WelcomeScreen
import com.niqob.app.feature.pairing.DevicePairingScreen
import com.niqob.app.feature.parent.ManageChildScreen
import com.niqob.app.feature.parent.ParentDashboardScreen
import com.niqob.app.feature.permissions.AppPermissionsScreen
import com.niqob.app.feature.safety.SafetyMonitoringScreen
import com.niqob.app.feature.screentime.ScreenTimeScreen
import com.niqob.app.feature.settings.SettingsScreen

@Composable
fun NiqobNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Welcome.route
) {
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
                    navController.navigate(Screen.ChildHome.route)
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
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
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
            AppPermissionsScreen(onNavigateBack = { navController.popBackStack() })
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
    }
}

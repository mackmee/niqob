package com.niqob.app.core.navigation

sealed class Screen(val route: String) {
    data object Welcome : Screen("welcome")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object ParentDashboard : Screen("parent_dashboard")
    data object ChildHome : Screen("child_home")


    // Parent feature destinations
    data object DevicePairing : Screen("device_pairing")
    data object ManageChild : Screen("manage_child")
    data object AppPermissions : Screen("app_permissions")
    data object ScreenTime : Screen("screen_time")
    data object AiSettings : Screen("ai_settings")
    data object SafetyMonitoring : Screen("safety_monitoring")
    data object Settings : Screen("settings")

    // Child feature destinations
    data object ChildPairing : Screen("child_pairing")
    data object ChildLearning : Screen("child_learning")
    data object ChildAi : Screen("child_ai")
    data object ChildAllowedApps : Screen("child_allowed_apps")
    data object ChildGoals : Screen("child_goals")
    data object UsageAccessVerification : Screen("usage_access_verification")
    data object ScreenTimeLimitReached : Screen("screen_time_limit_reached")
}

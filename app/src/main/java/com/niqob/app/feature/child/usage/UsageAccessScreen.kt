package com.niqob.app.feature.child.usage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.niqob.app.core.ui.components.NiqobCard
import com.niqob.app.core.ui.components.NiqobTopAppBar
import com.niqob.app.core.ui.components.StatRowItem
import com.niqob.app.core.ui.theme.AlertAmber
import com.niqob.app.core.ui.theme.SafeGreen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.AdminPanelSettings
import com.niqob.app.domain.model.EnforcementActionResult
import com.niqob.app.domain.model.EnforcementExecutionResult
import com.niqob.app.domain.model.AppUsageInfo
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementDecision
import com.niqob.app.domain.model.ForegroundAppInfo
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus

@Composable
fun UsageAccessScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    viewModel: UsageAccessViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = UsageAccessViewModel.provideFactory(context)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check permission automatically when user returns from system Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkPermissionAndRefresh()
                viewModel.refreshAccessibilityStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            NiqobTopAppBar(
                title = "Usage Access Verification",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.checkPermissionAndRefresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Status Card
            item {
                NiqobCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRowItem(
                            label = "Usage Access Permission",
                            value = if (uiState.isPermissionGranted) "Granted" else "Not granted",
                            icon = if (uiState.isPermissionGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            statusColor = if (uiState.isPermissionGranted) SafeGreen else AlertAmber
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        if (!uiState.isPermissionGranted) {
                            Text(
                                text = "Android requires special Usage Access permission to allow NIQOB to read screen-time statistics from the child device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = { launchUsageAccessSettings(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Usage Access Settings")
                            }
                        } else {
                            Text(
                                text = "Permission is active. Real usage statistics are being collected via Android UsageStatsManager.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SafeGreen
                            )
                        }
                    }
                }
            }

            // Loading Indicator
            if (uiState.isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Querying UsageStatsManager...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Error State
            if (uiState.errorMessage != null) {
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Failed to Query Usage",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.errorMessage ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.checkPermissionAndRefresh() },
                                modifier = Modifier.align(Alignment.End),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Today's Usage Summary & Daily Limit Status (Phase 3C)
            if (uiState.isPermissionGranted && uiState.todaySummary != null) {
                val summary = uiState.todaySummary!!
                val status = uiState.screenTimeStatus

                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.HourglassTop,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Today's Screen Time",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Dev Verification • Excludes NIQOB app",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (status != null) {
                                    Text(
                                        text = if (uiState.isSimulatedLimitActive) "[Dev Test]" else "[Server Limit]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (uiState.isSimulatedLimitActive) AlertAmber else SafeGreen
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (status != null) {
                                StatRowItem(
                                    label = "Used",
                                    value = "${status.usedMinutes} min"
                                )
                                StatRowItem(
                                    label = "Limit",
                                    value = "${status.dailyLimitMinutes} min"
                                )
                                StatRowItem(
                                    label = "Remaining",
                                    value = "${status.remainingMinutes} min"
                                )
                                StatRowItem(
                                    label = "Usage",
                                    value = "${status.usagePercentage}%"
                                )

                                val enforcementState = uiState.enforcementState
                                val statusDisplayName = enforcementState?.status?.displayName ?: status.statusText
                                val statusColor = when (enforcementState?.status) {
                                    ScreenTimeEnforcementStatus.DISABLED -> MaterialTheme.colorScheme.outline
                                    ScreenTimeEnforcementStatus.LIMIT_REACHED -> AlertAmber
                                    ScreenTimeEnforcementStatus.WITHIN_LIMIT -> SafeGreen
                                    null -> when {
                                        !status.isEnabled -> MaterialTheme.colorScheme.outline
                                        status.isLimitReached -> AlertAmber
                                        else -> SafeGreen
                                    }
                                }

                                StatRowItem(
                                    label = "Status",
                                    value = statusDisplayName,
                                    statusColor = statusColor
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                LinearProgressIndicator(
                                    progress = { status.progressFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = when {
                                        !status.isEnabled -> MaterialTheme.colorScheme.outline
                                        status.isLimitReached -> AlertAmber
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = "Test Limit Overrides (Dev Verification Only):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.setSimulatedLimit(30, true) },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("30m", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.setSimulatedLimit(0, true) },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("0m", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.setSimulatedLimit(null, false) },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("Disable", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.resetToLiveServerLimit() },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("Reset", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            } else {
                                val hours = summary.totalScreenTimeMinutes / 60
                                val minutes = summary.totalScreenTimeMinutes % 60
                                val displayTime = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"

                                Text(
                                    text = displayTime,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "${summary.totalScreenTimeMillis} ms • Date: ${summary.date}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Phase 3D: Cloud Screen Time Usage Sync Card
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Cloud Sync",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Cloud Screen Time Sync",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Daily aggregate sync to Supabase",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (uiState.isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            StatRowItem(
                                label = "Device ID",
                                value = uiState.childDeviceId ?: "Not paired",
                                icon = Icons.Default.Apps
                            )

                            StatRowItem(
                                label = "Automatic Background Sync",
                                value = if (uiState.isAutoSyncScheduled) "Active (every 15 min)" else "Scheduled",
                                icon = Icons.Default.Schedule,
                                statusColor = SafeGreen
                            )

                            val lastSync = uiState.lastSyncedUsage
                            if (lastSync != null) {
                                val syncDateStr = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                                    .format(java.util.Date(lastSync.updatedAt))

                                StatRowItem(
                                    label = "Cloud Sync Status",
                                    value = "Synced (${lastSync.totalUsageMinutes}m used • ${lastSync.remainingMinutes}m remaining)",
                                    icon = Icons.Default.CheckCircle,
                                    statusColor = SafeGreen
                                )

                                StatRowItem(
                                    label = "Last Synced Time",
                                    value = syncDateStr,
                                    icon = Icons.Default.HourglassTop
                                )
                            } else if (uiState.syncErrorMessage != null) {
                                StatRowItem(
                                    label = "Cloud Sync Status",
                                    value = "Error: ${uiState.syncErrorMessage}",
                                    icon = Icons.Default.Warning,
                                    statusColor = MaterialTheme.colorScheme.error
                                )
                            } else {
                                StatRowItem(
                                    label = "Cloud Sync Status",
                                    value = if (uiState.childDeviceId != null) "Ready to sync" else "Awaiting device pairing",
                                    icon = Icons.Default.HourglassTop
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = { viewModel.syncTodayUsage() },
                                enabled = !uiState.isSyncing && uiState.childDeviceId != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (uiState.isSyncing) "Syncing..." else "Sync to Supabase Now")
                            }
                        }
                    }
                }

                // Phase 4D-1: Current Foreground App Detection Card (Development Verification)
                item {
                    CurrentForegroundAppDevCard(
                        detectedApp = uiState.detectedForegroundApp,
                        decision = uiState.foregroundAppDecision,
                        isEnforcementActive = uiState.enforcementState?.isEnforcementActive == true,
                        isLoading = uiState.isDetectingForegroundApp,
                        isPermissionGranted = uiState.isPermissionGranted,
                        excludeSelf = uiState.excludeSelfFromDetection,
                        onRefresh = { viewModel.refreshForegroundAppDetection() },
                        onToggleExcludeSelf = { viewModel.setExcludeSelfFromDetection(it) },
                        onOpenSettings = { launchUsageAccessSettings(context) }
                    )
                }

                // Phase 4E: Enforcement Mechanism & Accessibility Bridge Card
                item {
                    EnforcementMechanismDevCard(
                        isControllerRunning = uiState.isEnforcementControllerRunning,
                        isDeviceOwnerActive = uiState.isDeviceOwnerActive,
                        isAccessibilityConnected = uiState.isAccessibilityServiceConnected,
                        lastResult = uiState.lastEnforcementExecutionResult,
                        detectedPackage = uiState.detectedForegroundApp?.packageName,
                        onStartController = { viewModel.startEnforcementController() },
                        onStopController = { viewModel.stopEnforcementController() },
                        onEvaluatePackage = { viewModel.evaluatePackageWithController(it) },
                        onOpenAccessibilitySettings = {
                            try {
                                context.startActivity(com.niqob.app.data.local.enforcement.AccessibilityServiceStatusHelper.createOpenSettingsIntent())
                            } catch (_: Exception) {}
                        }
                    )
                }

                // Phase 4C: App Enforcement Policy Evaluation Card (Development Verification)
                item {
                    AppEnforcementPolicyDevCard(
                        decisions = uiState.appPolicyDecisions,
                        isEnforcementActive = uiState.enforcementState?.isEnforcementActive == true,
                        allowedPackages = uiState.allowedPackagesInPolicy,
                        onToggleAllowedPackage = { pkg -> viewModel.toggleAllowPackageInPolicy(pkg) }
                    )
                }

                // App Usage Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Apps Used Today (${summary.appUsages.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Sorted by usage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (summary.appUsages.isEmpty()) {
                    item {
                        NiqobCard(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No app usage recorded on device today yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(summary.appUsages, key = { it.packageName }) { appUsage ->
                        AppUsageRowItem(appUsage = appUsage)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AppUsageRowItem(
    appUsage: AppUsageInfo,
    modifier: Modifier = Modifier
) {
    NiqobCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appUsage.appLabel ?: appUsage.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = appUsage.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                val mins = appUsage.totalTimeInForegroundMinutes
                val secs = appUsage.totalTimeInForegroundSeconds
                val durationText = if (mins > 0) "${mins} min" else "${secs}s"

                Text(
                    text = durationText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${appUsage.totalTimeInForegroundMillis} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun launchUsageAccessSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallbackIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {
            // In case device has no Usage Access settings activity
        }
    }
}

@Composable
private fun AppEnforcementPolicyDevCard(
    decisions: List<AppEnforcementDecision>,
    isEnforcementActive: Boolean,
    allowedPackages: Set<String>,
    onToggleAllowedPackage: (String) -> Unit
) {
    NiqobCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "App Enforcement Policy",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "App Enforcement Policy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Foreground App Policy Evaluation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = if (isEnforcementActive) AlertAmber.copy(alpha = 0.15f) else SafeGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isEnforcementActive) "ENFORCING" else "INACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnforcementActive) AlertAmber else SafeGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Simulated Evaluation of Sample Packages:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                decisions.forEach { decision ->
                    val isAllowed = decision.decision == AppEnforcementAction.ALLOW
                    val badgeColor = if (isAllowed) SafeGreen else AlertAmber
                    val isCustomAllowed = allowedPackages.contains(decision.packageName)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = decision.packageName,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Box(
                                modifier = Modifier
                                    .background(
                                        color = badgeColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isAllowed) "ALLOW" else "BLOCK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = badgeColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = decision.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Quick toggle button for testing explicitly allowed apps
                        if (decision.packageName != "com.niqob.app") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = { onToggleAllowedPackage(decision.packageName) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = if (isCustomAllowed) "Remove from Policy" else "Allow in Policy",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Note: Pure policy evaluation preview. No real third-party applications are blocked in Phase 4C.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CurrentForegroundAppDevCard(
    detectedApp: ForegroundAppInfo?,
    decision: AppEnforcementDecision?,
    isEnforcementActive: Boolean,
    isLoading: Boolean,
    isPermissionGranted: Boolean,
    excludeSelf: Boolean,
    onRefresh: () -> Unit,
    onToggleExcludeSelf: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    NiqobCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = "Foreground Detection",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Current Foreground App",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Detection Prototype (Phase 4D-1)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = if (isEnforcementActive) AlertAmber.copy(alpha = 0.15f) else SafeGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isEnforcementActive) "ENFORCING" else "INACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnforcementActive) AlertAmber else SafeGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!isPermissionGranted) {
                Text(
                    text = "Usage Access permission is required to detect foreground applications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Usage Access Settings")
                }
            } else if (detectedApp != null) {
                val isAllowed = decision?.decision == AppEnforcementAction.ALLOW
                val badgeColor = if (isAllowed) SafeGreen else AlertAmber

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = detectedApp.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (decision != null) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = badgeColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isAllowed) "ALLOW" else "BLOCK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = badgeColor
                                )
                            }
                        }
                    }

                    if (decision != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Reason: ${decision.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Enforcement Active: ${decision.enforcementActive}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (decision.enforcementActive) AlertAmber else SafeGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(detectedApp.timestampMillis))
                    Text(
                        text = "Detection time: $dateStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "No foreground application event recorded yet. Open another app (e.g. Settings or Chrome) and return to refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Safety Warning Notice
            Text(
                text = "Preview only — no app is being blocked.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onToggleExcludeSelf(!excludeSelf) },
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (excludeSelf) "Exclude NIQOB: ON" else "Exclude NIQOB: OFF",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Button(
                    onClick = onRefresh,
                    enabled = !isLoading && isPermissionGranted,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh Detection", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnforcementMechanismDevCard(
    isControllerRunning: Boolean,
    isDeviceOwnerActive: Boolean,
    isAccessibilityConnected: Boolean,
    lastResult: EnforcementExecutionResult?,
    detectedPackage: String?,
    onStartController: () -> Unit,
    onStopController: () -> Unit,
    onEvaluatePackage: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    NiqobCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Screen Time Enforcement",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Accessibility Bridge: ${if (isAccessibilityConnected) "ENABLED" else "DISABLED"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAccessibilityConnected) SafeGreen else AlertAmber
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(
                            if (isControllerRunning) SafeGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isControllerRunning) "ACTIVE" else "STOPPED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isControllerRunning) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subsystem statuses
            StatRowItem(
                label = "BYOD: Accessibility Service",
                value = if (isAccessibilityConnected) "Connected (Active)" else "Disabled (Manual Setup Required)",
                icon = Icons.Default.SettingsAccessibility,
                statusColor = if (isAccessibilityConnected) SafeGreen else AlertAmber
            )

            if (!isAccessibilityConnected) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsAccessibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Accessibility Settings", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            StatRowItem(
                label = "Managed: Device Owner (DPM)",
                value = if (isDeviceOwnerActive) "Active (Provisioned)" else "Not Provisioned (Standard BYOD)",
                icon = Icons.Default.AdminPanelSettings,
                statusColor = if (isDeviceOwnerActive) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Last execution outcome
            if (lastResult != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lastResult.packageName,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )

                        val badgeColor = when (lastResult.actionResult) {
                            EnforcementActionResult.PREVIEW_ALLOW -> SafeGreen
                            EnforcementActionResult.PREVIEW_BLOCK -> AlertAmber
                            EnforcementActionResult.ENFORCED_HOME_REDIRECT -> AlertAmber
                            EnforcementActionResult.NOT_SUPPORTED -> MaterialTheme.colorScheme.error
                            EnforcementActionResult.ERROR -> MaterialTheme.colorScheme.error
                        }

                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = lastResult.actionResult.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Mechanism: ${lastResult.mechanismName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = lastResult.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Real block executed: ${lastResult.isRealBlockExecuted}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (lastResult.isRealBlockExecuted) AlertAmber else SafeGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Safety Warning Notice
            Text(
                text = "Controlled Prototype — sends HOME on blocked apps, no process killing or overlays.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Controller lifecycle controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isControllerRunning) {
                    Button(
                        onClick = onStartController,
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Start Controller", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = onStopController,
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Stop Controller", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (detectedPackage != null) {
                    OutlinedButton(
                        onClick = { onEvaluatePackage(detectedPackage) },
                        enabled = isControllerRunning,
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Evaluate Current", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}




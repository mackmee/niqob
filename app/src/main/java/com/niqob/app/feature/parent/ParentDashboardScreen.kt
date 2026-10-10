package com.niqob.app.feature.parent

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niqob.app.core.ui.components.NiqobCard
import com.niqob.app.core.ui.components.NiqobTopAppBar
import com.niqob.app.core.ui.components.StatRowItem
import com.niqob.app.core.ui.theme.AlertAmber
import com.niqob.app.core.ui.theme.SafeGreen
import com.niqob.app.domain.model.PairingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.Color
import com.niqob.app.domain.model.DeviceConnectivityStatus


@Composable
fun ParentDashboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateToManageChild: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToScreenTime: () -> Unit,
    onNavigateToAiSettings: () -> Unit,
    onNavigateToSafety: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToScreenTimeRequests: () -> Unit = {},
    onLogout: () -> Unit = onNavigateBack,
    modifier: Modifier = Modifier,
    viewModel: ParentDashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val status = uiState.childStatus
    val session = uiState.pairingSession
    val isConnected = session?.status == PairingStatus.CONNECTED
    val user = uiState.user

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshDashboard()
    }

    Scaffold(
        topBar = {
            NiqobTopAppBar(
                title = "NIQOB Parent",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.logout(onLogout) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Sign Out",
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Parent Account Banner
            item {
                Spacer(modifier = Modifier.height(4.dp))
                NiqobCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SupervisorAccount,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.displayName ?: "Parent Account",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = user?.email ?: "Signed In",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.logout(onLogout) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Sign Out", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Child Status",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Child Status Card
            item {
                NiqobCard {
                    StatRowItem(
                        label = "Child Device",
                        value = if (isConnected) "Connected" else "Not connected",
                        icon = Icons.Default.Smartphone,
                        statusColor = if (isConnected) SafeGreen else AlertAmber
                    )

                    if (session != null && session.status == PairingStatus.CONNECTED) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        // Connectivity / Heartbeat status (ONLINE / RECENTLY_ONLINE / OFFLINE)
                        val connectivityColor = when (uiState.connectivityStatus) {
                            DeviceConnectivityStatus.ONLINE -> SafeGreen
                            DeviceConnectivityStatus.RECENTLY_ONLINE -> AlertAmber
                            DeviceConnectivityStatus.OFFLINE -> Color.Gray
                        }
                        val connectivityLabel = when (uiState.connectivityStatus) {
                            DeviceConnectivityStatus.ONLINE -> "ONLINE"
                            DeviceConnectivityStatus.RECENTLY_ONLINE -> "RECENTLY ONLINE"
                            DeviceConnectivityStatus.OFFLINE -> "OFFLINE"
                        }
                        val connectivityIcon = if (uiState.connectivityStatus == DeviceConnectivityStatus.OFFLINE) {
                            Icons.Default.WifiOff
                        } else {
                            Icons.Default.Wifi
                        }

                        StatRowItem(
                            label = "Connectivity",
                            value = connectivityLabel,
                            icon = connectivityIcon,
                            statusColor = connectivityColor
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        StatRowItem(
                            label = "Device ID",
                            value = session.childDeviceId ?: "Child Device",
                            icon = Icons.Default.CheckCircle
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        StatRowItem(
                            label = "Pairing Status",
                            value = session.status.name,
                            icon = Icons.Default.Link
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        val formattedDate = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                            .format(Date(session.createdAt))
                        StatRowItem(
                            label = "Connected",
                            value = formattedDate,
                            icon = Icons.Default.CalendarToday
                        )
                    }


                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    StatRowItem(
                        label = "Screen Time",
                        value = if (uiState.isScreenTimeEnabled) {
                            "${uiState.screenTimeLimitMinutes} min"
                        } else {
                            "Disabled"
                        },
                        icon = Icons.Default.HourglassTop
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    StatRowItem(
                        label = "Allowed Apps",
                        value = "${status.allowedAppsCount}",
                        icon = Icons.Default.Apps
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    StatRowItem(
                        label = "Safety Alerts",
                        value = "${status.safetyAlertsCount}",
                        icon = Icons.Default.NotificationsActive,
                        statusColor = if (status.safetyAlertsCount > 0) AlertAmber else SafeGreen
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isConnected) {
                        Button(
                            onClick = onNavigateToManageChild,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.SupervisorAccount,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Manage Child")
                        }
                    } else {
                        Button(
                            onClick = onNavigateToPairing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect Child Device")
                        }
                    }
                }
            }

            // Screen Time Card
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Screen Time Management",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                ParentScreenTimeCard(
                    uiState = uiState,
                    onPresetSelected = { viewModel.updateScreenTimeMinutes(it) },
                    onCustomMinutesChanged = { viewModel.updateScreenTimeMinutes(it) },
                    onToggleEnabled = { viewModel.toggleScreenTimeEnabled(it) },
                    onSave = { viewModel.saveScreenTimeLimit() }
                )
            }

            // Phase 3D: Cloud Screen Time Usage Verification Section
            item {
                ParentScreenTimeUsageCard(
                    uiState = uiState,
                    onRefreshUsage = { viewModel.loadTodayUsage() }
                )
            }

            // Phase 4F-2: Screen Time Requests Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Screen Time Requests",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                ParentActionCard(
                    title = "Screen Time Requests",
                    subtitle = "Review and approve pending extra screen time requests from child",
                    icon = Icons.Default.HourglassTop,
                    onClick = onNavigateToScreenTimeRequests
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Controls & Management",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Management Buttons / Placeholders
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ParentActionCard(
                        title = if (isConnected) "Device Pairing" else "Connect Child Device",
                        subtitle = if (isConnected) "View or reconfigure device pairing" else "Pair with child tablet or phone",
                        icon = Icons.Default.Link,
                        onClick = onNavigateToPairing
                    )
                    ParentActionCard(
                        title = "App Permissions",
                        subtitle = "Manage installed and blocked apps",
                        icon = Icons.Default.Apps,
                        onClick = onNavigateToPermissions
                    )
                    ParentActionCard(
                        title = "Screen Time",
                        subtitle = "Set daily limits and downtime schedule",
                        icon = Icons.Default.HourglassTop,
                        onClick = onNavigateToScreenTime
                    )
                    ParentActionCard(
                        title = "AI Settings",
                        subtitle = "Tutor guidance rules & boundaries",
                        icon = Icons.Default.Psychology,
                        onClick = onNavigateToAiSettings
                    )
                    ParentActionCard(
                        title = "Safety",
                        subtitle = "Real-time alerts & environment monitoring",
                        icon = Icons.Default.Security,
                        onClick = onNavigateToSafety
                    )
                    ParentActionCard(
                        title = "Settings",
                        subtitle = "Parent PIN, accounts, and system options",
                        icon = Icons.Default.Settings,
                        onClick = onNavigateToSettings
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ParentActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NiqobCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ParentScreenTimeUsageCard(
    uiState: ParentDashboardUiState,
    onRefreshUsage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val usage = uiState.todayUsage
    val isConnected = uiState.pairingSession?.status == com.niqob.app.domain.model.PairingStatus.CONNECTED || uiState.childDeviceId != null

    NiqobCard(modifier = modifier.fillMaxWidth()) {
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
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = "Usage Sync",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Today's Screen Time Usage",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isConnected) "Synced from Supabase" else "Child device not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (uiState.isUsageLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = onRefreshUsage,
                        enabled = isConnected
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Usage",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!isConnected) {
                Text(
                    text = "Pair a child device to view real-time screen time usage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (usage != null) {
                // Exact fields required:
                // Used, Limit, Remaining, Percentage, Status, Usage date, Last synced time
                StatRowItem(
                    label = "Used",
                    value = "${usage.totalUsageMinutes} min",
                    icon = Icons.Default.HourglassTop
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                StatRowItem(
                    label = "Limit",
                    value = "${usage.dailyLimitMinutes} min",
                    icon = Icons.Default.Settings
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                StatRowItem(
                    label = "Remaining",
                    value = "${usage.remainingMinutes} min",
                    icon = Icons.Default.CheckCircle,
                    statusColor = if (usage.remainingMinutes > 0) com.niqob.app.core.ui.theme.SafeGreen else com.niqob.app.core.ui.theme.AlertAmber
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                StatRowItem(
                    label = "Percentage",
                    value = "${usage.usagePercentage}%",
                    icon = Icons.Default.Apps,
                    statusColor = if (usage.isLimitReached) com.niqob.app.core.ui.theme.AlertAmber else com.niqob.app.core.ui.theme.SafeGreen
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                StatRowItem(
                    label = "Status",
                    value = usage.statusText,
                    icon = if (usage.isLimitReached) Icons.Default.Warning else Icons.Default.CheckCircle,
                    statusColor = if (usage.isLimitReached) com.niqob.app.core.ui.theme.AlertAmber else com.niqob.app.core.ui.theme.SafeGreen
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                StatRowItem(
                    label = "Usage date",
                    value = usage.usageDate,
                    icon = Icons.Default.CalendarToday
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                val syncedDateStr = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(usage.updatedAt))
                StatRowItem(
                    label = "Last synced time",
                    value = syncedDateStr,
                    icon = Icons.Default.Sync,
                    statusColor = com.niqob.app.core.ui.theme.SafeGreen
                )
            } else if (uiState.usageError != null) {
                StatRowItem(
                    label = "Sync Error",
                    value = uiState.usageError,
                    icon = Icons.Default.Warning,
                    statusColor = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRefreshUsage,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Retry")
                }
            } else {
                Text(
                    text = "No usage recorded yet for today (${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}). Once the child device reports usage, it will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRefreshUsage,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Check for Usage")
                }
            }
        }
    }
}


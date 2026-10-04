package com.niqob.app.feature.child

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

@Composable
fun ChildHomeScreen(
    onNavigateBack: () -> Unit,
    onNavigateToConnectParent: () -> Unit,
    onNavigateToLearning: () -> Unit,
    onNavigateToAi: () -> Unit,
    onNavigateToAllowedApps: () -> Unit,
    onNavigateToGoals: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToUsageVerification: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChildHomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            NiqobTopAppBar(
                title = "NIQOB Child",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack
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
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.greeting,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Parent Device Connection Status Card
            item {
                NiqobCard {
                    StatRowItem(
                        label = "Parent Device",
                        value = if (uiState.isParentConnected) "Connected" else "Not connected",
                        icon = Icons.Default.FamilyRestroom,
                        statusColor = if (uiState.isParentConnected) SafeGreen else AlertAmber
                    )

                    if (!uiState.isParentConnected) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onNavigateToConnectParent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect to Parent")
                        }
                    }
                }
            }

            // Child Sections
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChildSectionCard(
                        title = "Learning",
                        description = "Explore interactive lessons and discovery quests",
                        icon = Icons.Default.School,
                        onClick = onNavigateToLearning
                    )
                    ChildSectionCard(
                        title = "NIQOB AI",
                        description = "Ask your safe AI buddy anything you're curious about",
                        icon = Icons.Default.AutoAwesome,
                        onClick = onNavigateToAi
                    )
                    ChildSectionCard(
                        title = "Allowed Apps",
                        description = "Access safe games and educational applications",
                        icon = Icons.Default.Apps,
                        onClick = onNavigateToAllowedApps
                    )
                    ChildSectionCard(
                        title = "My Goals",
                        description = "Track daily achievements, habits, and stars",
                        icon = Icons.Default.EmojiEvents,
                        onClick = onNavigateToGoals
                    )
                    ChildSectionCard(
                        title = "Settings",
                        description = "Audio, avatar customizations, and preferences",
                        icon = Icons.Default.Settings,
                        onClick = onNavigateToSettings
                    )
                    ChildSectionCard(
                        title = "Usage Access Verification",
                        description = "Verify real Android UsageStats and today's screen time",
                        icon = Icons.Default.HourglassTop,
                        onClick = onNavigateToUsageVerification
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
private fun ChildSectionCard(
    title: String,
    description: String,
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
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

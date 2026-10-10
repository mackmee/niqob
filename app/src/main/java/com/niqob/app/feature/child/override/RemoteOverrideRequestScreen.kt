package com.niqob.app.feature.child.override

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niqob.app.core.ui.components.NiqobCard
import com.niqob.app.core.ui.components.NiqobTopAppBar
import com.niqob.app.core.ui.components.StatRowItem
import com.niqob.app.core.ui.theme.AlertAmber
import com.niqob.app.core.ui.theme.AlertRed
import com.niqob.app.core.ui.theme.SafeGreen
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteOverrideRequestScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: RemoteOverrideRequestViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeRequest = uiState.activeRequest
    val isActivationSuccess = uiState.isActivationSuccess
    val activatedSession = uiState.activatedSession

    Scaffold(
        topBar = {
            NiqobTopAppBar(
                title = "Request More Screen Time",
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Header Hero Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val heroIcon = when {
                        isActivationSuccess -> Icons.Default.LockOpen
                        activeRequest?.status == RemoteOverrideRequestStatus.APPROVED -> Icons.Default.CheckCircle
                        activeRequest?.status == RemoteOverrideRequestStatus.REJECTED -> Icons.Default.Warning
                        activeRequest?.status == RemoteOverrideRequestStatus.EXPIRED -> Icons.Default.TimerOff
                        else -> Icons.Default.HourglassEmpty
                    }
                    val heroColor = when {
                        isActivationSuccess -> SafeGreen
                        activeRequest?.status == RemoteOverrideRequestStatus.APPROVED -> SafeGreen
                        activeRequest?.status == RemoteOverrideRequestStatus.REJECTED -> AlertRed
                        activeRequest?.status == RemoteOverrideRequestStatus.EXPIRED -> Color.Gray
                        else -> AlertAmber
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(heroColor.copy(alpha = 0.15f), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = heroIcon,
                            contentDescription = "Screen Time Request",
                            tint = heroColor,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isActivationSuccess) {
                            "Screen time activated."
                        } else if (activeRequest != null) {
                            "Screen Time Request"
                        } else {
                            "Request More Screen Time"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isActivationSuccess) {
                            "Your parent's override is now active on this device."
                        } else if (activeRequest != null) {
                            "Request status for extra screen time"
                        } else {
                            "Ask your parent for additional screen time today."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Error Banner if present
            if (uiState.errorMessage != null) {
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearError() }) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // State A: Activation Success Banner & Details
            if (isActivationSuccess && activatedSession != null) {
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = SafeGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Screen time activated.",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SafeGreen
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            StatRowItem(
                                label = "Duration Granted",
                                value = "${activatedSession.durationMinutes} min",
                                icon = Icons.Default.Timer
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            val formattedExpiration = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date(activatedSession.expiresAtMillis))
                            StatRowItem(
                                label = "Expires At",
                                value = formattedExpiration,
                                icon = Icons.Default.Timer,
                                statusColor = SafeGreen
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            StatRowItem(
                                label = "Time Remaining",
                                value = "${activatedSession.remainingMinutes()} min",
                                icon = Icons.Default.HourglassEmpty,
                                statusColor = SafeGreen
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                    ) {
                        Text("Return to Apps")
                    }
                }
            } else if (activeRequest != null) {
                // State B: Active Request Status Card
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Submission acknowledgment banner
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Request sent to your parent.",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Requested Duration
                            StatRowItem(
                                label = "Requested Duration",
                                value = "${activeRequest.requestedDurationMinutes} min",
                                icon = Icons.Default.Timer
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            // Status Row with domain status mapping
                            val (statusText, statusColor) = when (activeRequest.status) {
                                RemoteOverrideRequestStatus.PENDING -> "Waiting for parent approval" to AlertAmber
                                RemoteOverrideRequestStatus.APPROVED -> "Your parent approved this request." to SafeGreen
                                RemoteOverrideRequestStatus.REJECTED -> "Your parent rejected this request." to AlertRed
                                RemoteOverrideRequestStatus.EXPIRED -> "This request has expired." to Color.Gray
                                RemoteOverrideRequestStatus.CONSUMED -> "Override has been consumed." to SafeGreen
                                RemoteOverrideRequestStatus.REVOKED -> "Your parent revoked this override." to AlertRed
                            }

                            StatRowItem(
                                label = "Status",
                                value = statusText,
                                icon = when (activeRequest.status) {
                                    RemoteOverrideRequestStatus.APPROVED -> Icons.Default.CheckCircle
                                    RemoteOverrideRequestStatus.REJECTED -> Icons.Default.Warning
                                    RemoteOverrideRequestStatus.EXPIRED -> Icons.Default.TimerOff
                                    else -> Icons.Default.HourglassEmpty
                                },
                                statusColor = statusColor
                            )

                            // Reason if provided
                            if (!activeRequest.reason.isNullOrBlank()) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                StatRowItem(
                                    label = "Your Reason",
                                    value = activeRequest.reason,
                                    icon = Icons.AutoMirrored.Filled.Send
                                )
                            }

                            // Rejection reason if provided
                            if (!activeRequest.rejectionReason.isNullOrBlank()) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                StatRowItem(
                                    label = "Parent Note",
                                    value = activeRequest.rejectionReason,
                                    icon = Icons.Default.Warning,
                                    statusColor = AlertRed
                                )
                            }

                            // Claim expiration time if available
                            val claimExpiresAtMillis = activeRequest.claimExpiresAtMillis
                            if (claimExpiresAtMillis != null && activeRequest.status == RemoteOverrideRequestStatus.PENDING) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                val formattedDate = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                    .format(Date(claimExpiresAtMillis))
                                StatRowItem(
                                    label = "Claim Expires At",
                                    value = formattedDate,
                                    icon = Icons.Default.Timer
                                )
                            }

                            // Approved banner & activation action
                            if (activeRequest.status == RemoteOverrideRequestStatus.APPROVED) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SafeGreen.copy(alpha = 0.12f))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Approved — tap Continue to activate.",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = SafeGreen
                                        )
                                        if (activeRequest.approvedDurationMinutes != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Granted: ${activeRequest.approvedDurationMinutes} minutes",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = { viewModel.continueToActivate() },
                                    enabled = uiState.canActivate && !uiState.isActivating,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                                ) {
                                    if (uiState.isActivating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Activating...")
                                    } else {
                                        Text("Continue to activate")
                                    }
                                }
                            }
                        }
                    }
                }

                // Check Status Button (Manual refresh, when pending)
                if (activeRequest.status == RemoteOverrideRequestStatus.PENDING) {
                    item {
                        Button(
                            onClick = { viewModel.refreshStatus() },
                            enabled = !uiState.isRefreshing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Checking...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Check Status")
                            }
                        }
                    }
                }

                // Allow creating a new request if terminal (REJECTED / EXPIRED / CONSUMED / REVOKED)
                if (activeRequest.status != RemoteOverrideRequestStatus.PENDING &&
                    activeRequest.status != RemoteOverrideRequestStatus.APPROVED
                ) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.resetToNewRequest() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Make Another Request")
                        }
                    }
                }
            } else {
                // State C: Request Creation Form
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "How much more time do you need?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Duration Chips: 15 min, 30 min, 60 min
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RemoteOverrideRequest.VALID_DURATIONS_MINUTES.sorted().forEach { duration ->
                                    FilterChip(
                                        selected = uiState.selectedDurationMinutes == duration,
                                        onClick = { viewModel.onDurationSelected(duration) },
                                        label = { Text("$duration min") }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Optional Reason Field
                            OutlinedTextField(
                                value = uiState.reason,
                                onValueChange = { viewModel.onReasonChanged(it) },
                                label = { Text("Reason (Optional)") },
                                placeholder = { Text("e.g., Working on homework") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 3,
                                supportingText = {
                                    Text(
                                        text = "${uiState.reason.length}/${RemoteOverrideRequest.MAX_REASON_LENGTH}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.submitRequest() },
                                enabled = uiState.canSubmit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (uiState.isSubmitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sending...")
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Send Request")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

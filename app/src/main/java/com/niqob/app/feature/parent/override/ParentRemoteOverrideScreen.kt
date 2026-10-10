package com.niqob.app.feature.parent.override

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentRemoteOverrideScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ParentRemoteOverrideViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var approvingRequest by remember { mutableStateOf<RemoteOverrideRequest?>(null) }
    var selectedApprovalDuration by remember { mutableIntStateOf(15) }

    var rejectingRequest by remember { mutableStateOf<RemoteOverrideRequest?>(null) }
    var rejectionReason by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadPendingRequests()
    }

    Scaffold(
        topBar = {
            NiqobTopAppBar(
                title = "Screen Time Requests",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = { viewModel.loadPendingRequests() },
                        enabled = !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Requests",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Header
            item {
                Text(
                    text = "Pending Override Requests",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Review and grant time-bounded screen-time extensions for your children.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Success feedback
            if (uiState.successMessage != null) {
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SafeGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = uiState.successMessage ?: "",
                                color = SafeGreen,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearMessages() }) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Error feedback
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
                                contentDescription = null,
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
                            TextButton(onClick = { viewModel.clearMessages() }) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Empty state
            if (!uiState.isLoading && uiState.pendingRequests.isEmpty()) {
                item {
                    NiqobCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(SafeGreen.copy(alpha = 0.15f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = SafeGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "No Pending Requests",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "There are no screen-time override requests waiting for approval.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Pending request items
            items(uiState.pendingRequests, key = { it.id }) { request ->
                PendingRequestItem(
                    request = request,
                    isActionInProgress = uiState.actionInProgressRequestId == request.id,
                    onApproveClick = {
                        selectedApprovalDuration = request.requestedDurationMinutes
                        approvingRequest = request
                    },
                    onRejectClick = {
                        rejectionReason = ""
                        rejectingRequest = request
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Approve Dialog
    approvingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { approvingRequest = null },
            title = { Text("Approve Screen Time") },
            text = {
                Column {
                    Text(
                        text = "Grant additional screen time for Child Device. Default is the requested ${request.requestedDurationMinutes} minutes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Select Approved Duration:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RemoteOverrideRequest.VALID_DURATIONS_MINUTES.sorted().forEach { duration ->
                            FilterChip(
                                selected = selectedApprovalDuration == duration,
                                onClick = { selectedApprovalDuration = duration },
                                label = { Text("$duration min") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val reqId = request.id
                        approvingRequest = null
                        viewModel.approveRequest(reqId, selectedApprovalDuration)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Text("Approve ($selectedApprovalDuration min)")
                }
            },
            dismissButton = {
                TextButton(onClick = { approvingRequest = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Reject Dialog
    rejectingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { rejectingRequest = null },
            title = { Text("Reject Screen Time Request") },
            text = {
                Column {
                    Text(
                        text = "Reject this request from Child Device. You may optionally include an explanation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = rejectionReason,
                        onValueChange = {
                            if (it.length <= RemoteOverrideRequest.MAX_REASON_LENGTH) {
                                rejectionReason = it
                            }
                        },
                        label = { Text("Rejection Note (Optional)") },
                        placeholder = { Text("e.g., Bedtime in 20 minutes") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        supportingText = {
                            Text("${rejectionReason.length}/${RemoteOverrideRequest.MAX_REASON_LENGTH}")
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val reqId = request.id
                        val note = rejectionReason
                        rejectingRequest = null
                        viewModel.rejectRequest(reqId, note)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Reject")
                }
            },
            dismissButton = {
                TextButton(onClick = { rejectingRequest = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PendingRequestItem(
    request: RemoteOverrideRequest,
    isActionInProgress: Boolean,
    onApproveClick: () -> Unit,
    onRejectClick: () -> Unit
) {
    NiqobCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Device & Requested Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Child Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .background(AlertAmber.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${request.requestedDurationMinutes} min requested",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AlertAmber
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Reason if present
            if (!request.reason.isNullOrBlank()) {
                StatRowItem(
                    label = "Child Reason",
                    value = request.reason,
                    icon = Icons.Default.HourglassTop
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Requested At
            val formattedCreated = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                .format(Date(request.createdAtMillis))
            StatRowItem(
                label = "Requested At",
                value = formattedCreated,
                icon = Icons.Default.Timer
            )

            // Claim Expiration
            val claimExpiresMillis = request.claimExpiresAtMillis
            if (claimExpiresMillis != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                val formattedExpires = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                    .format(Date(claimExpiresMillis))
                StatRowItem(
                    label = "Claim Expires",
                    value = formattedExpires,
                    icon = Icons.Default.Timer,
                    statusColor = AlertAmber
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            if (isActionInProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRejectClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Reject", color = AlertRed)
                    }

                    Button(
                        onClick = onApproveClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                    ) {
                        Text("Approve")
                    }
                }
            }
        }
    }
}

package com.niqob.app.feature.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.niqob.app.core.ui.components.NiqobCard
import com.niqob.app.core.ui.theme.SafeGreen
import com.niqob.app.domain.model.PairingStatus

@Composable
fun ParentScreenTimeCard(
    uiState: ParentDashboardUiState,
    onPresetSelected: (Int) -> Unit,
    onCustomMinutesChanged: (Int) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = uiState.pairingSession?.status == PairingStatus.CONNECTED || uiState.childDeviceId != null

    NiqobCard(modifier = modifier.fillMaxWidth()) {
        // Header Row
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
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = "Screen Time",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daily Screen Time",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        !isConnected -> "Child device not connected"
                        !uiState.isScreenTimeEnabled -> "Disabled"
                        else -> "Active: ${formatScreenTimeSummary(uiState.screenTimeLimitMinutes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected && uiState.isScreenTimeEnabled) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isConnected) {
                Switch(
                    checked = uiState.isScreenTimeEnabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = !uiState.isScreenTimeSaving && !uiState.isScreenTimeLoading
                )
            }
        }

        if (!isConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Connect a child device to configure daily screen time limits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Confirmed Server Limit
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Confirmed Server Limit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isScreenTimeLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (uiState.isScreenTimeEnabled) {
                            formatScreenTimeDetailed(uiState.screenTimeLimitMinutes)
                        } else {
                            "Disabled"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (uiState.isScreenTimeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Presets
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h")
                presets.forEach { (mins, label) ->
                    val isSelected = uiState.selectedScreenTimeMinutes == mins
                    if (isSelected) {
                        Button(
                            onClick = { onPresetSelected(mins) },
                            enabled = uiState.isScreenTimeEnabled && !uiState.isScreenTimeSaving,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(label, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPresetSelected(mins) },
                            enabled = uiState.isScreenTimeEnabled && !uiState.isScreenTimeSaving,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            // Custom Limit Stepper & Direct Input
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Custom Limit (minutes)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val newVal = (uiState.selectedScreenTimeMinutes - 15).coerceAtLeast(0)
                        onCustomMinutesChanged(newVal)
                    },
                    enabled = uiState.isScreenTimeEnabled && !uiState.isScreenTimeSaving && uiState.selectedScreenTimeMinutes > 0,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Remove, contentDescription = "Decrease 15 min")
                }

                Spacer(modifier = Modifier.width(8.dp))

                val focusManager = LocalFocusManager.current
                var textVal by remember(uiState.selectedScreenTimeMinutes) {
                    mutableStateOf(uiState.selectedScreenTimeMinutes.toString())
                }

                OutlinedTextField(
                    value = textVal,
                    onValueChange = { input ->
                        val digitsOnly = input.filter { it.isDigit() }.take(4)
                        textVal = digitsOnly
                        val num = digitsOnly.toIntOrNull() ?: 0
                        onCustomMinutesChanged(num)
                    },
                    enabled = uiState.isScreenTimeEnabled && !uiState.isScreenTimeSaving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    suffix = { Text("min") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalIconButton(
                    onClick = {
                        val newVal = (uiState.selectedScreenTimeMinutes + 15).coerceAtMost(1440)
                        onCustomMinutesChanged(newVal)
                    },
                    enabled = uiState.isScreenTimeEnabled && !uiState.isScreenTimeSaving && uiState.selectedScreenTimeMinutes < 1440,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Increase 15 min")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Selected: ${formatScreenTimeDetailed(uiState.selectedScreenTimeMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Error Banner
            if (uiState.screenTimeError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.screenTimeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Save Success Banner
            if (uiState.screenTimeSaveSuccess) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SafeGreen.copy(alpha = 0.15f))
                        .border(1.dp, SafeGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Saved",
                            tint = SafeGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Saved and confirmed by server: ${formatScreenTimeDetailed(uiState.screenTimeLimitMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = SafeGreen
                        )
                    }
                }
            }

            // Save Button
            Spacer(modifier = Modifier.height(16.dp))
            val isValidRange = uiState.selectedScreenTimeMinutes in 0..1440
            val canSave = isConnected && !uiState.isScreenTimeSaving && !uiState.isScreenTimeLoading && isValidRange

            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (uiState.isScreenTimeSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saving to Server...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Daily Limit")
                }
            }
        }
    }
}

fun formatScreenTimeSummary(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m (${minutes} min)"
        h > 0 -> "${h}h (${minutes} min)"
        else -> "${minutes} min"
    }
}

fun formatScreenTimeDetailed(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min ($minutes mins)"
        h > 0 -> "$h hr${if (h > 1) "s" else ""} ($minutes mins)"
        else -> "$minutes mins"
    }
}

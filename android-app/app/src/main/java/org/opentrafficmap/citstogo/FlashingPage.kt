package org.opentrafficmap.citstogo

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun FlashingPage(
    state: FirmwareFlashingState,
    bridgeRunning: Boolean,
    onRetryRelease: () -> Unit,
    onChooseCustomFirmware: () -> Unit,
    onUseReleaseFirmware: () -> Unit,
    onFlash: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ESP32-C5 firmware", style = MaterialTheme.typography.titleMedium)
        Text(
            "Install the firmware artifact matching this app version, or select a custom merged firmware.bin file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        FlashingDetail("App version", state.appVersion)
        FlashingDetail("Source", if (state.customFirmware) "Custom file" else state.releaseTag ?: "Checking…")
        FlashingDetail("Firmware", state.firmwareName ?: "Checking…")
        FlashingDetail("Device", state.deviceName ?: "Waiting for ESP32-C5…")
        Text(
            if (bridgeRunning && state.phase == FirmwareFlashingPhase.Ready) {
                "Stop the receiver on the Home page before flashing."
            } else {
                state.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (state.phase) {
                FirmwareFlashingPhase.Error -> Color(ContextCompat.getColor(context, R.color.error))
                FirmwareFlashingPhase.Complete -> Color(ContextCompat.getColor(context, R.color.success_variant))
                else -> MaterialTheme.colorScheme.secondary
            },
        )
        if (state.busy || state.phase == FirmwareFlashingPhase.Complete) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        if (state.phase == FirmwareFlashingPhase.Error && state.releaseTag == null && !state.customFirmware) {
            Button(onClick = onRetryRelease, modifier = Modifier.fillMaxWidth()) {
                Text("Retry release lookup")
            }
        }
        Button(
            onClick = onChooseCustomFirmware,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.customFirmware) "Choose another firmware.bin" else "Choose custom firmware.bin")
        }
        if (state.customFirmware) {
            TextButton(
                onClick = onUseReleaseFirmware,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use matching release instead")
            }
        }
    }
    if (state.phase == FirmwareFlashingPhase.Ready ||
        (state.phase == FirmwareFlashingPhase.Error && state.firmwareName != null && state.deviceName != null)
    ) {
        var position by rememberSaveable(state.deviceName, state.message) { mutableStateOf(0f) }
        var submitted by rememberSaveable(state.deviceName, state.message) { mutableStateOf(false) }
        Text(
            "Put the ESP32-C5 into boot mode before flashing: hold the BOOT button while connecting or resetting the board, and keep it held until flashing starts. Keep USB connected until verification finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(ContextCompat.getColor(context, R.color.tertiary)),
            fontWeight = FontWeight.SemiBold,
        )
        TxApprovalSlider(
            position = position,
            onPositionChange = {
                position = it
                if (!submitted && it >= 0.995f) {
                    submitted = true
                    position = 1f
                    onFlash()
                }
            },
            enabled = !bridgeRunning,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Slide fully to flash", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FlashingDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(88.dp), fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

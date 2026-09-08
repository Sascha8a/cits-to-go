package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.bridge.BridgeStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun CamBroadcastPage(
    status: BridgeStatus,
    logLine: String,
    intervalMs: String,
    onIntervalChange: (String) -> Unit,
    onConfigure: (Boolean) -> Unit,
) {
    CamPanel(
        status = status,
        intervalMs = intervalMs,
        onIntervalChange = onIntervalChange,
        onConfigure = onConfigure,
    )
    if (logLine.isNotBlank() || status.lastError.isNotBlank()) {
        EventLog(logLine, status.lastError)
    }
}

@Composable
private fun CamPanel(
    status: BridgeStatus,
    intervalMs: String,
    onIntervalChange: (String) -> Unit,
    onConfigure: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("CAM broadcast", style = MaterialTheme.typography.titleMedium)
        Text(
            "ETSI CAM Release 1 over GeoNetworking SHB/BTP-B. Location values come from Android; unavailable sensor values are explicitly encoded as unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedTextField(
            value = intervalMs,
            onValueChange = onIntervalChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !status.camEnabled,
            label = { Text("Broadcast interval") },
            suffix = { Text("ms") },
            supportingText = { Text("Allowed CAM range: 100–1000 ms") },
        )
        Button(
            onClick = { onConfigure(!status.camEnabled) },
            enabled = status.running,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = if (status.camEnabled) {
                ButtonDefaults.buttonColors(containerColor = Color(ContextCompat.getColor(context, R.color.error)))
            } else {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            },
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (status.camEnabled) "Stop CAM broadcast" else "Start CAM broadcast")
        }
    }
}

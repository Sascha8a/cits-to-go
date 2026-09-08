package org.opentrafficmap.citstogo

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import org.opentrafficmap.citstogo.srem.SremProfile

@Composable
fun SettingsPage(
    mqttUri: String,
    nodeId: String,
    maxQueueLength: String,
    maxQueueAgeSeconds: String,
    txApproved: Boolean,
    sremProfile: SremProfile,
    bridgeRunning: Boolean,
    bluetoothEnrollmentRunning: Boolean,
    bluetoothEnrollmentMessage: String,
    bluetoothEnrollmentError: Boolean,
    debugMenuEnabled: Boolean,
    onDebugMenuEnabledChange: (Boolean) -> Unit,
    onEnrollBluetooth: () -> Unit,
    onRevokeTxApproval: () -> Unit,
    onSave: (String, String, String, String, SremProfile) -> Unit,
) {
    val context = LocalContext.current
    var draftMqttUri by rememberSaveable(mqttUri) { mutableStateOf(mqttUri) }
    var draftNodeId by rememberSaveable(nodeId) { mutableStateOf(nodeId) }
    var draftMaxQueueLength by rememberSaveable(maxQueueLength) { mutableStateOf(maxQueueLength) }
    var draftMaxQueueAgeSeconds by rememberSaveable(maxQueueAgeSeconds) { mutableStateOf(maxQueueAgeSeconds) }
    var draftSremProfile by rememberSaveable(sremProfile) { mutableStateOf(sremProfile) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draftMqttUri,
            onValueChange = { draftMqttUri = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("MQTT broker") },
            placeholder = { Text("mqtt://broker.example:1883") },
        )
        OutlinedTextField(
            value = draftNodeId,
            onValueChange = { draftNodeId = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Node ID") },
        )
        OutlinedTextField(
            value = draftMaxQueueLength,
            onValueChange = { draftMaxQueueLength = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Max queue length") },
            placeholder = { Text("100") },
        )
        OutlinedTextField(
            value = draftMaxQueueAgeSeconds,
            onValueChange = { draftMaxQueueAgeSeconds = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Max queue age") },
            placeholder = { Text("0.2") },
            suffix = { Text("s") },
        )
        Text("SREM vehicle profile", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                val index = SremProfile.entries.indexOf(draftSremProfile)
                draftSremProfile = SremProfile.entries[(index - 1 + SremProfile.entries.size) % SremProfile.entries.size]
            }) { Text("Previous") }
            Text(draftSremProfile.displayName, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                val index = SremProfile.entries.indexOf(draftSremProfile)
                draftSremProfile = SremProfile.entries[(index + 1) % SremProfile.entries.size]
            }) { Text("Next") }
        }
        HorizontalDivider()
        Text("Bluetooth", style = MaterialTheme.typography.titleMedium)
        Text(
            "Enrollment uses USB once. Afterwards the Bluetooth bond secures normal connections.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Button(
            onClick = onEnrollBluetooth,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !bridgeRunning && !bluetoothEnrollmentRunning,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (bluetoothEnrollmentRunning) "Enrolling…" else "Enroll this phone for Bluetooth")
        }
        if (bridgeRunning) {
            Text(
                "Stop the active connection before enrollment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else if (bluetoothEnrollmentMessage.isNotBlank()) {
            Text(
                bluetoothEnrollmentMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (bluetoothEnrollmentError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Debug menu", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Show firmware and transport diagnostics in the navigation drawer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Switch(
                checked = debugMenuEnabled,
                onCheckedChange = onDebugMenuEnabledChange,
            )
        }
        Button(
            onClick = onRevokeTxApproval,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = txApproved,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(ContextCompat.getColor(context, R.color.error))),
        ) {
            Text(if (txApproved) "Revoke TX Approval" else "TX Approval not active")
        }
        Button(
            onClick = {
                onSave(
                    draftMqttUri,
                    draftNodeId,
                    draftMaxQueueLength,
                    draftMaxQueueAgeSeconds,
                    draftSremProfile,
                )
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Save")
        }
    }
}

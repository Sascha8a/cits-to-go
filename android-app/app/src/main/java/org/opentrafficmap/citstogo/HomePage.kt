package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.ConnectionMode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomePage(
    devices: List<android.hardware.usb.UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    connectionMode: ConnectionMode,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    status: BridgeStatus,
    recentActivity: List<ActivityLogEntry>,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
) {
    val packetsPerSecond = rememberPacketRate(status.packets, status.running)
    StatusBand(status, connectionMode, packetsPerSecond)
    ConfigPanel(
        devices = devices,
        selectedDeviceName = selectedDeviceName,
        onSelectDevice = onSelectDevice,
        connectionMode = connectionMode,
        onConnectionModeChange = onConnectionModeChange,
        status = status,
        onRefresh = onRefresh,
    )
    ActionControls(
        status = status,
        onStart = onStart,
        onStop = onStop,
        onStartPcap = onStartPcap,
        onStopPcap = onStopPcap,
        onStartReplay = onStartReplay,
        onStopReplay = onStopReplay,
    )
    Metrics(status, packetsPerSecond)
    LastPacketCard(status.lastPacketSummary)
    RecentActivity(recentActivity)
}

package org.opentrafficmap.citstogo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.opentrafficmap.citstogo.update.CodebergAppUpdateChecker

@Composable
fun AboutPage() {
    val context = LocalContext.current
    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("C-ITS to go", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.secondary)
        Text(
            "CITS-to-go is an experimental, portable C-ITS capture and transmit bridge built around an Android phone and a Seeed Studio XIAO ESP32-C5.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "It can capture C-ITS packets, stream them to Android over USB, save captures as PCAP, forward packets to MQTT, transmit raw 802.11 frames, and generate configurable ETSI CAM messages from phone location data.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "The project is heavily inspired by and derived from OpenTrafficMap and is not an official OpenTrafficMap release.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Source code on Codeberg",
            modifier = Modifier.clickable { open(CodebergAppUpdateChecker.REPOSITORY_URL) },
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "OpenTrafficMap",
            modifier = Modifier.clickable { open("https://opentrafficmap.org") },
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

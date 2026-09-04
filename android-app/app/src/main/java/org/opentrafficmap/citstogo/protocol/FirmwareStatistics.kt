package org.opentrafficmap.citstogo.protocol

import java.io.Serializable

data class FirmwareStatistics(
    val uptimeMs: Long,
    val sampleMs: Long,
    val wifiRxPacketsPerSecond: Long,
    val capturedPacketsPerSecond: Long,
    val usbCapturePacketsPerSecond: Long,
    val bleCapturePacketsPerSecond: Long,
    val usbBytesPerSecond: Long,
    val bleBytesPerSecond: Long,
    val bleNotificationsPerSecond: Long,
    val rxNoBufferTotal: Long,
    val rxTooLargeTotal: Long,
    val bleInputDropsTotal: Long,
    val usbOutputDropsTotal: Long,
    val bleOutputDropsTotal: Long,
    val usbPartialWriteDropsTotal: Long,
    val bleNotifyFailuresTotal: Long,
    val wifiRxPacketsTotal: Long,
    val capturedPacketsTotal: Long,
    val usbCapturePacketsTotal: Long,
    val bleCapturePacketsTotal: Long,
    val flags: Long,
    val usbQueueDepth: Int,
    val usbQueueCapacity: Int,
    val bleQueueDepth: Int,
    val bleQueueCapacity: Int,
    val bleMtu: Int,
    val bleConnectionIntervalUnits: Int,
    val bleConnectionLatency: Int,
    val bleSupervisionTimeoutUnits: Int,
    val bleTxPhy: Int,
    val bleRxPhy: Int,
) : Serializable {
    val usbConnected: Boolean get() = flags and FLAG_USB_CONNECTED != 0L
    val bleConnected: Boolean get() = flags and FLAG_BLE_CONNECTED != 0L
    val bleNotificationsEnabled: Boolean get() = flags and FLAG_BLE_NOTIFY_ENABLED != 0L
    val bleSecured: Boolean get() = flags and FLAG_BLE_SECURED != 0L
    val bleConnectionIntervalMs: Double? get() = bleConnectionIntervalUnits.takeIf { it > 0 }?.times(1.25)
    val bleSupervisionTimeoutMs: Int? get() = bleSupervisionTimeoutUnits.takeIf { it > 0 }?.times(10)

    companion object {
        const val FLAG_USB_CONNECTED = 1L shl 0
        const val FLAG_BLE_CONNECTED = 1L shl 1
        const val FLAG_BLE_NOTIFY_ENABLED = 1L shl 2
        const val FLAG_BLE_SECURED = 1L shl 3
    }
}

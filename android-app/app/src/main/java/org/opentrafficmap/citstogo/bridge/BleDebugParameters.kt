package org.opentrafficmap.citstogo.bridge

import java.io.Serializable

data class BleDebugParameters(
    val deviceAddress: String,
    val mtu: Int,
    val txPhy: Int,
    val rxPhy: Int,
    val highPriorityRequested: Boolean,
    val preferred2MPhyRequested: Boolean,
    val queuedNotifications: Int,
) : Serializable

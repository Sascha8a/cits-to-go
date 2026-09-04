package org.opentrafficmap.citstogo.protocol

import java.util.zip.CRC32

sealed interface CtgInboundFrame {
    data class Capture(val packet: CitsPacket) : CtgInboundFrame
    data class TxResult(
        val requestId: Long,
        val status: Long,
        val packetLength: Int,
        val packet: ByteArray? = null,
    ) : CtgInboundFrame {
        val successful: Boolean get() = status == 0L
    }
    data class BluetoothEnrollmentResult(
        val status: Long,
        val armed: Boolean,
    ) : CtgInboundFrame {
        val successful: Boolean get() = status == 0L && armed
    }
    data class Statistics(val statistics: FirmwareStatistics) : CtgInboundFrame
}

class CtgFrameDecoder {
    private val decoded = ByteArray(MAX_RECORD_BYTES)

    fun decode(encoded: ByteArray, length: Int = encoded.size): CtgInboundFrame {
        val decodedLength = Cobs.decodeInto(encoded, length, decoded)
        if (decodedLength < MIN_HEADER_LEN + CRC_LEN) throw ProtocolException("Frame too short")
        if (decoded[0] != 'C'.code.toByte() ||
            decoded[1] != 'T'.code.toByte() ||
            decoded[2] != 'G'.code.toByte() ||
            decoded[3] != '1'.code.toByte()
        ) {
            throw ProtocolException("Bad CTG1 magic")
        }
        val version = decoded[4].toInt() and 0xff
        val type = decoded[5].toInt() and 0xff
        val headerLen = u16(decoded, 6)
        if (version != 1) throw ProtocolException("Unsupported CTG version $version")
        if (headerLen < MIN_HEADER_LEN || decodedLength < headerLen + CRC_LEN) {
            throw ProtocolException("Invalid CTG header length $headerLen")
        }

        val expectedCrc = u32(decoded, decodedLength - CRC_LEN)
        val crc = CRC32()
        crc.update(decoded, 0, decodedLength - CRC_LEN)
        val actualCrc = crc.value
        if (actualCrc != expectedCrc) {
            throw ProtocolException("CRC mismatch")
        }

        return when (type) {
            TYPE_CAPTURE -> decodeCapture(decodedLength, headerLen)
            TYPE_TX_RESULT -> decodeTxResult(decodedLength, headerLen)
            TYPE_BLE_ENROLL_RESULT -> decodeBluetoothEnrollmentResult(decodedLength, headerLen)
            TYPE_STATISTICS -> decodeStatistics(decodedLength, headerLen)
            else -> throw ProtocolException("Unsupported CTG frame type $type")
        }
    }

    private fun decodeCapture(decodedLength: Int, headerLen: Int): CtgInboundFrame.Capture {
        if (headerLen != CAPTURE_HEADER_LEN) throw ProtocolException("Unexpected capture header length $headerLen")
        val capturedLen = u16(decoded, 26)
        val totalLen = headerLen + capturedLen + CRC_LEN
        if (decodedLength != totalLen) {
            throw ProtocolException("Frame length $decodedLength does not match captured length $capturedLen")
        }
        val payload = decoded.copyOfRange(headerLen, headerLen + capturedLen)
        return CtgInboundFrame.Capture(CitsPacket(
            sequence = u32(decoded, 10),
            timestampUs = u64(decoded, 14),
            frequencyMhz = u16(decoded, 22),
            rssiDbm = decoded[28].toInt(),
            wifiType = decoded[29].toInt() and 0xff,
            rxState = decoded[30].toInt() and 0xff,
            flags = u16(decoded, 8),
            originalLength = u16(decoded, 24),
            capturedLength = capturedLen,
            payload = payload,
        ))
    }

    private fun decodeTxResult(decodedLength: Int, headerLen: Int): CtgInboundFrame.TxResult {
        if (headerLen != TX_RESULT_HEADER_LEN || decodedLength < headerLen + CRC_LEN) {
            throw ProtocolException("Malformed TX result")
        }
        val packetLength = u16(decoded, 16)
        val payloadLength = decodedLength - headerLen - CRC_LEN
        if (payloadLength != 0 && payloadLength != packetLength) {
            throw ProtocolException("TX result payload length $payloadLength does not match packet length $packetLength")
        }
        return CtgInboundFrame.TxResult(
            requestId = u32(decoded, 8),
            status = u32(decoded, 12),
            packetLength = packetLength,
            packet = if (payloadLength > 0) decoded.copyOfRange(headerLen, headerLen + payloadLength) else null,
        )
    }

    private fun decodeBluetoothEnrollmentResult(decodedLength: Int, headerLen: Int): CtgInboundFrame.BluetoothEnrollmentResult {
        if (headerLen != BLE_ENROLL_RESULT_HEADER_LEN || decodedLength != headerLen + CRC_LEN) {
            throw ProtocolException("Malformed Bluetooth enrollment result")
        }
        return CtgInboundFrame.BluetoothEnrollmentResult(
            status = u32(decoded, 8),
            armed = decoded[12].toInt() != 0,
        )
    }

    private fun decodeStatistics(decodedLength: Int, headerLen: Int): CtgInboundFrame.Statistics {
        if (headerLen != STATISTICS_HEADER_LEN || decodedLength != headerLen + CRC_LEN) {
            throw ProtocolException("Malformed statistics record")
        }
        return CtgInboundFrame.Statistics(FirmwareStatistics(
            uptimeMs = u32(decoded, 8),
            sampleMs = u32(decoded, 12),
            wifiRxPacketsPerSecond = u32(decoded, 16),
            capturedPacketsPerSecond = u32(decoded, 20),
            usbCapturePacketsPerSecond = u32(decoded, 24),
            bleCapturePacketsPerSecond = u32(decoded, 28),
            usbBytesPerSecond = u32(decoded, 32),
            bleBytesPerSecond = u32(decoded, 36),
            bleNotificationsPerSecond = u32(decoded, 40),
            rxNoBufferTotal = u32(decoded, 44),
            rxTooLargeTotal = u32(decoded, 48),
            bleInputDropsTotal = u32(decoded, 52),
            usbOutputDropsTotal = u32(decoded, 56),
            bleOutputDropsTotal = u32(decoded, 60),
            usbPartialWriteDropsTotal = u32(decoded, 64),
            bleNotifyFailuresTotal = u32(decoded, 68),
            wifiRxPacketsTotal = u32(decoded, 72),
            capturedPacketsTotal = u32(decoded, 76),
            usbCapturePacketsTotal = u32(decoded, 80),
            bleCapturePacketsTotal = u32(decoded, 84),
            flags = u32(decoded, 88),
            usbQueueDepth = u16(decoded, 92),
            usbQueueCapacity = u16(decoded, 94),
            bleQueueDepth = u16(decoded, 96),
            bleQueueCapacity = u16(decoded, 98),
            bleMtu = u16(decoded, 100),
            bleConnectionIntervalUnits = u16(decoded, 102),
            bleConnectionLatency = u16(decoded, 104),
            bleSupervisionTimeoutUnits = u16(decoded, 106),
            bleTxPhy = decoded[108].toInt() and 0xff,
            bleRxPhy = decoded[109].toInt() and 0xff,
        ))
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(buf: ByteArray, offset: Int): Long =
        (u16(buf, offset).toLong()) or (u16(buf, offset + 2).toLong() shl 16)

    private fun u64(buf: ByteArray, offset: Int): Long {
        val low = u32(buf, offset)
        val high = u32(buf, offset + 4)
        return low or (high shl 32)
    }

    companion object {
        const val TYPE_CAPTURE = 1
        const val TYPE_TX_REQUEST = 2
        const val TYPE_TX_RESULT = 3
        const val TYPE_BLE_ENROLL_REQUEST = 4
        const val TYPE_BLE_ENROLL_RESULT = 5
        const val TYPE_STATISTICS = 6
        const val CAPTURE_HEADER_LEN = 32
        const val TX_REQUEST_HEADER_LEN = 16
        const val TX_RESULT_HEADER_LEN = 20
        const val BLE_ENROLL_REQUEST_HEADER_LEN = 12
        const val BLE_ENROLL_RESULT_HEADER_LEN = 16
        const val STATISTICS_HEADER_LEN = 112
        const val MIN_HEADER_LEN = 8
        const val CRC_LEN = 4
        private const val MAX_RECORD_BYTES = 8192
    }
}

class ProtocolException(message: String) : Exception(message)

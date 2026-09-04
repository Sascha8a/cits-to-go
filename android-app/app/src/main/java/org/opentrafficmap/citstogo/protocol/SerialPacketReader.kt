package org.opentrafficmap.citstogo.protocol

class SerialPacketReader(
    private val onPacket: (CitsPacket) -> Unit,
    private val onTxResult: (CtgInboundFrame.TxResult) -> Unit,
    private val onProtocolError: (String) -> Unit,
    private val onStatistics: (FirmwareStatistics) -> Unit = {},
) {
    private val decoder = CtgFrameDecoder()
    private val record = ByteArray(MAX_ENCODED_RECORD)
    private var recordLength = 0
    private var discarding = false

    fun accept(buffer: ByteArray, count: Int) {
        require(count in 0..buffer.size)
        for (i in 0 until count) {
            val value = buffer[i]
            if (value == 0.toByte()) {
                finishRecord()
                discarding = false
                recordLength = 0
            } else if (!discarding) {
                if (recordLength < record.size) {
                    record[recordLength++] = value
                } else {
                    recordLength = 0
                    discarding = true
                    onProtocolError("Serial record exceeded $MAX_ENCODED_RECORD bytes; resynchronizing")
                }
            }
        }
    }

    private fun finishRecord() {
        if (discarding || recordLength == 0) return
        try {
            when (val frame = decoder.decode(record, recordLength)) {
                is CtgInboundFrame.Capture -> onPacket(frame.packet)
                is CtgInboundFrame.TxResult -> onTxResult(frame)
                is CtgInboundFrame.Statistics -> onStatistics(frame.statistics)
                // Enrollment is handled by BluetoothEnrollment over its dedicated USB
                // control exchange, not by the normal capture/bridge stream. Ignore a
                // stray enrollment acknowledgement here while keeping the sealed
                // frame dispatch exhaustive.
                is CtgInboundFrame.BluetoothEnrollmentResult -> Unit
            }
        } catch (e: Exception) {
            onProtocolError(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_ENCODED_RECORD = 8192
    }
}

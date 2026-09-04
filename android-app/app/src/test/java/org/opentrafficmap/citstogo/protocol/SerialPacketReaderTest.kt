package org.opentrafficmap.citstogo.protocol

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Test

class SerialPacketReaderTest {
    @Test
    fun coalescedRecordsDecodeFromOneChunk() {
        var results = 0
        var errors = 0
        val reader = SerialPacketReader(
            onPacket = {},
            onTxResult = { results += 1 },
            onProtocolError = { errors += 1 },
        )
        val first = txResult(1)
        val second = txResult(2)
        val chunk = first + byteArrayOf(0) + second + byteArrayOf(0)

        reader.accept(chunk, chunk.size)

        assertEquals(2, results)
        assertEquals(0, errors)
    }

    @Test
    fun oversizedRecordIsDiscardedThroughDelimiterAndReaderResynchronizes() {
        var results = 0
        var errors = 0
        val reader = SerialPacketReader(
            onPacket = {},
            onTxResult = { results += 1 },
            onProtocolError = { errors += 1 },
        )

        val oversized = ByteArray(9_000) { 1 }
        reader.accept(oversized, oversized.size)
        reader.accept(byteArrayOf(0), 1)
        val valid = txResult(7) + byteArrayOf(0)
        reader.accept(valid, valid.size)

        assertEquals(1, errors)
        assertEquals(1, results)
    }

    private fun txResult(requestId: Long): ByteArray {
        val decoded = ByteArray(CtgFrameDecoder.TX_RESULT_HEADER_LEN + CtgFrameDecoder.CRC_LEN)
        "CTG1".toByteArray(Charsets.US_ASCII).copyInto(decoded, 0)
        decoded[4] = 1
        decoded[5] = CtgFrameDecoder.TYPE_TX_RESULT.toByte()
        putU16(decoded, 6, CtgFrameDecoder.TX_RESULT_HEADER_LEN)
        putU32(decoded, 8, requestId)
        putU32(decoded, 12, 0)
        putU16(decoded, 16, 0)
        putU16(decoded, 18, 0)
        val crc = CRC32().apply { update(decoded, 0, decoded.size - CtgFrameDecoder.CRC_LEN) }.value
        putU32(decoded, decoded.size - CtgFrameDecoder.CRC_LEN, crc)
        return Cobs.encode(decoded)
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        putU16(bytes, offset, value.toInt())
        putU16(bytes, offset + 2, (value ushr 16).toInt())
    }
}

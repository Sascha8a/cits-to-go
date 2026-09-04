package org.opentrafficmap.citstogo.protocol

/** Counts missing CTG capture sequence numbers, including across u32 wrap-around. */
class CaptureSequenceTracker {
    private var previous: Long? = null

    fun reset() {
        previous = null
    }

    fun observe(sequence: Long): Long {
        val current = sequence and U32_MASK
        val last = previous
        previous = current
        if (last == null) return 0

        val delta = (current - last) and U32_MASK
        return if (delta in 2..MAX_FORWARD_DELTA) delta - 1 else 0
    }

    private companion object {
        const val U32_MASK = 0xffff_ffffL
        const val MAX_FORWARD_DELTA = 0x7fff_ffffL
    }
}

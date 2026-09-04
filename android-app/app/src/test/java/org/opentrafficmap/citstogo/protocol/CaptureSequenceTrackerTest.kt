package org.opentrafficmap.citstogo.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSequenceTrackerTest {
    @Test
    fun countsForwardGaps() {
        val tracker = CaptureSequenceTracker()
        assertEquals(0, tracker.observe(10))
        assertEquals(0, tracker.observe(11))
        assertEquals(3, tracker.observe(15))
    }

    @Test
    fun handlesU32WrapWithoutFalseDrops() {
        val tracker = CaptureSequenceTracker()
        assertEquals(0, tracker.observe(0xffff_fffeL))
        assertEquals(0, tracker.observe(0xffff_ffffL))
        assertEquals(0, tracker.observe(0))
        assertEquals(1, tracker.observe(2))
    }

    @Test
    fun resetAndBackwardJumpStartNewBaseline() {
        val tracker = CaptureSequenceTracker()
        tracker.observe(100)
        assertEquals(0, tracker.observe(50))
        tracker.reset()
        assertEquals(0, tracker.observe(1_000))
    }
}

package imagesorter.sync.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReasonTest {

    @Test
    fun retryableReasonsAreClassifiedRetryable() {
        listOf("size_mismatch", "placement_error", "internal_error").forEach { wire ->
            assertTrue(wire, FailureReason.fromWire(wire).retryable)
        }
    }

    @Test
    fun terminalReasonsAreClassifiedTerminal() {
        listOf("unreadable", "no_video_destination").forEach { wire ->
            assertFalse(wire, FailureReason.fromWire(wire).retryable)
        }
    }

    @Test
    fun unknownAndNullMapToTerminalUnknown() {
        assertEquals(FailureReason.UNKNOWN, FailureReason.fromWire("something_new"))
        assertEquals(FailureReason.UNKNOWN, FailureReason.fromWire(null))
        assertFalse(FailureReason.fromWire(null).retryable)
    }
}

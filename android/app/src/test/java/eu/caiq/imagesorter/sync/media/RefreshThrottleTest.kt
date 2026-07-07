package eu.caiq.imagesorter.sync.media

import eu.caiq.imagesorter.sync.data.media.RefreshThrottle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 30-second floor between auto-refreshes. `now` is injected as epoch millis so the
 * decision is driven deterministically without a real clock; [RefreshThrottle.markRefreshed]
 * doubles as the manual-refresh reset (a pull records "now" so the next auto-refresh waits).
 */
class RefreshThrottleTest {

    @Test
    fun allowsRefreshWhenNothingHasEverRefreshed() {
        val throttle = RefreshThrottle()
        assertTrue(throttle.canAutoRefresh(now = 0L))
        assertTrue(throttle.canAutoRefresh(now = 1_000_000L))
    }

    @Test
    fun blocksRefreshUntilThirtySecondsElapseSinceTheLastRefresh() {
        val throttle = RefreshThrottle()
        throttle.markRefreshed(now = 1_000L)

        assertFalse(throttle.canAutoRefresh(now = 1_000L))            // same instant
        assertFalse(throttle.canAutoRefresh(now = 1_000L + 29_999L))  // just under 30s
        assertTrue(throttle.canAutoRefresh(now = 1_000L + 30_000L))   // exactly 30s
        assertTrue(throttle.canAutoRefresh(now = 1_000L + 45_000L))   // well past
    }

    @Test
    fun markRefreshedResetsTheFloorFromTheNewInstant() {
        val throttle = RefreshThrottle()
        throttle.markRefreshed(now = 0L)
        // The floor has elapsed 40s later...
        assertTrue(throttle.canAutoRefresh(now = 40_000L))

        // ...but a fresh markRefreshed (e.g. a manual pull) resets the floor from that instant,
        // independent of the earlier refresh time: 10s later is blocked again.
        throttle.markRefreshed(now = 40_000L)
        assertFalse(throttle.canAutoRefresh(now = 50_000L))
        assertTrue(throttle.canAutoRefresh(now = 70_000L))
    }
}

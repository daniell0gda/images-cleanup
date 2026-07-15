package imagesorter.sync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure app-open auto-sync gate: true only on an unmetered network when no successful full
 * sync finished within the last [AUTO_SYNC_THROTTLE_MILLIS]. Drives the full truth table.
 */
class AutoSyncGateTest {

    private val now = 1_000_000_000L

    @Test
    fun unmeteredWithNoPriorRunIsEligible() {
        assertTrue(
            shouldAutoSyncOnOpen(
                isNetworkAllowed = true,
                lastFullSyncAtMillis = null,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun unmeteredWithStaleRunIsEligible() {
        assertTrue(
            shouldAutoSyncOnOpen(
                isNetworkAllowed = true,
                lastFullSyncAtMillis = now - AUTO_SYNC_THROTTLE_MILLIS - 1,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun unmeteredExactlyAtThrottleBoundaryIsEligible() {
        assertTrue(
            shouldAutoSyncOnOpen(
                isNetworkAllowed = true,
                lastFullSyncAtMillis = now - AUTO_SYNC_THROTTLE_MILLIS,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun unmeteredWithRecentRunIsThrottled() {
        assertFalse(
            shouldAutoSyncOnOpen(
                isNetworkAllowed = true,
                lastFullSyncAtMillis = now - (AUTO_SYNC_THROTTLE_MILLIS - 1),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun meteredIsNeverEligibleRegardlessOfElapsedTime() {
        assertFalse(
            shouldAutoSyncOnOpen(
                isNetworkAllowed = false,
                lastFullSyncAtMillis = null,
                nowMillis = now,
            ),
        )
        assertFalse(
            shouldAutoSyncOnOpen(
                isNetworkAllowed = false,
                lastFullSyncAtMillis = now - AUTO_SYNC_THROTTLE_MILLIS - 1,
                nowMillis = now,
            ),
        )
    }
}

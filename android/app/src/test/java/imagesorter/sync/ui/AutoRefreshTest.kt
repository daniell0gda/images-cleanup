package imagesorter.sync.ui

import imagesorter.sync.data.media.RefreshThrottle
import imagesorter.sync.ui.screens.autoRefreshLoop
import imagesorter.sync.ui.screens.refreshNow
import imagesorter.sync.ui.screens.shouldAutoRefresh
import imagesorter.sync.ui.screens.syncCompletionRefreshLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-refresh gate predicate and the extracted polling loop. The loop is driven on a
 * virtual clock via `runTest`: it is launched in [kotlinx.coroutines.test.TestScope.backgroundScope]
 * (auto-cancelled at test end) and stepped with the scheduler's `runCurrent`/`advanceTimeBy`; the
 * injected `now` reads the scheduler's `currentTime` so the clock tracks virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRefreshTest {

    @Test
    fun gateOpenOnlyAtTopWithNoInteractionActive() {
        assertTrue(
            shouldAutoRefresh(
                firstVisibleItemIndex = 0,
                isSeekActive = false,
                previewOpen = false,
                selectionActive = false,
            ),
        )
    }

    @Test
    fun gateClosedWhileSegmentModeIsActive() {
        // PhotosScreen passes `segment != null` as this flag; while a segment is shown the gate
        // stays closed so auto-refresh never yanks the segment view out from under the user.
        val segmentActive = true
        assertFalse(
            shouldAutoRefresh(0, isSeekActive = segmentActive, previewOpen = false, selectionActive = false),
        )
    }

    @Test
    fun gateClosedWhenAnySingleConditionIsViolated() {
        assertFalse(shouldAutoRefresh(1, isSeekActive = false, previewOpen = false, selectionActive = false))
        assertFalse(shouldAutoRefresh(0, isSeekActive = true, previewOpen = false, selectionActive = false))
        assertFalse(shouldAutoRefresh(0, isSeekActive = false, previewOpen = true, selectionActive = false))
        assertFalse(shouldAutoRefresh(0, isSeekActive = false, previewOpen = false, selectionActive = true))
    }

    @Test
    fun performsLeadingEdgeRefreshImmediatelyWhenFloorIsStale() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        var refreshes = 0
        backgroundScope.launch {
            autoRefreshLoop(
                now = { scheduler.currentTime },
                isRefreshing = { false },
                gateOpen = { true },
                throttle = throttle,
                refresh = { refreshes++ },
            )
        }

        scheduler.runCurrent() // first iteration evaluates before any delay
        assertEquals(1, refreshes)
    }

    @Test
    fun refreshesOnEveryThirtySecondTickWhileGateOpen() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        val refreshTimes = mutableListOf<Long>()
        backgroundScope.launch {
            autoRefreshLoop(
                now = { scheduler.currentTime },
                isRefreshing = { false },
                gateOpen = { true },
                throttle = throttle,
                refresh = { refreshTimes.add(scheduler.currentTime) },
            )
        }

        scheduler.runCurrent()                          // leading edge at t=0
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()   // t=30s
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()   // t=60s

        assertEquals(listOf(0L, 30_000L, 60_000L), refreshTimes)
    }

    @Test
    fun skipsTickWhileGateClosedThenRefreshesOnceWhenItReopens() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        var gate = false
        var refreshes = 0
        backgroundScope.launch {
            autoRefreshLoop(
                now = { scheduler.currentTime },
                isRefreshing = { false },
                gateOpen = { gate },
                throttle = throttle,
                refresh = { refreshes++ },
            )
        }

        scheduler.runCurrent()                          // t=0, gate closed → skip
        assertEquals(0, refreshes)
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()   // t=30s, still closed → skip
        assertEquals(0, refreshes)

        gate = true
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()   // t=60s, reopened → exactly one refresh (no replay of skips)
        assertEquals(1, refreshes)
    }

    @Test
    fun doesNotStartRefreshWhileOneIsAlreadyInFlight() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        var refreshing = true
        var refreshes = 0
        backgroundScope.launch {
            autoRefreshLoop(
                now = { scheduler.currentTime },
                isRefreshing = { refreshing },
                gateOpen = { true },
                throttle = throttle,
                refresh = { refreshes++ },
            )
        }

        scheduler.runCurrent()                          // t=0, in-flight → skip
        assertEquals(0, refreshes)
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()   // t=30s, still in-flight → skip
        assertEquals(0, refreshes)

        refreshing = false
        scheduler.advanceTimeBy(30_000); scheduler.runCurrent()   // t=60s, cleared → refresh
        assertEquals(1, refreshes)
    }

    @Test
    fun honorsFloorAcrossTicksAfterAnAutoRefresh() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        var refreshes = 0
        backgroundScope.launch {
            autoRefreshLoop(
                now = { scheduler.currentTime },
                isRefreshing = { false },
                gateOpen = { true },
                throttle = throttle,
                refresh = { refreshes++ },
                pollInterval = 10_000L, // ticks at 10s/20s/30s prove the 30s floor is independent of cadence
            )
        }

        scheduler.runCurrent()                          // t=0, leading edge fires
        assertEquals(1, refreshes)
        scheduler.advanceTimeBy(10_000); scheduler.runCurrent()   // t=10s, floor not elapsed → skip
        scheduler.advanceTimeBy(10_000); scheduler.runCurrent()   // t=20s, floor not elapsed → skip
        assertEquals(1, refreshes)
        scheduler.advanceTimeBy(10_000); scheduler.runCurrent()   // t=30s, floor elapsed → fires
        assertEquals(2, refreshes)
    }

    @Test
    fun tabOpenRefreshBypassesFloorButSkipsWhileRefreshInFlight() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        throttle.markRefreshed(now = scheduler.currentTime) // a refresh just happened: the floor is fresh
        var refreshes = 0

        // Opening the tab while a refresh is already in flight is skipped.
        refreshNow(
            now = { scheduler.currentTime },
            isRefreshing = { true },
            throttle = throttle,
            refresh = { refreshes++ },
        )
        assertEquals(0, refreshes)

        // Opening the tab with no refresh in flight fires immediately, even though the 30s
        // floor has NOT elapsed — the tab-open refresh bypasses the floor.
        refreshNow(
            now = { scheduler.currentTime },
            isRefreshing = { false },
            throttle = throttle,
            refresh = { refreshes++ },
        )
        assertEquals(1, refreshes)
    }

    @Test
    fun refreshesTimelineOnEachReportedSyncCompletion() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        val completions = MutableStateFlow(0)
        var refreshing = false
        var refreshes = 0
        backgroundScope.launch {
            syncCompletionRefreshLoop(
                completions = completions,
                now = { scheduler.currentTime },
                isRefreshing = { refreshing },
                throttle = throttle,
                refresh = { refreshes++ },
            )
        }

        scheduler.runCurrent()                          // subscribed; the initial value is not a completion
        assertEquals(0, refreshes)

        completions.value = 1; scheduler.runCurrent()   // a batch completed + reported → refresh
        assertEquals(1, refreshes)

        refreshing = true
        completions.value = 2; scheduler.runCurrent()   // completion while a refresh is in flight → skipped
        assertEquals(1, refreshes)

        refreshing = false
        completions.value = 3; scheduler.runCurrent()   // next completion refreshes
        assertEquals(2, refreshes)
    }

    @Test
    fun honorsFloorAcrossTicksAfterASimulatedManualRefresh() = runTest {
        val scheduler = testScheduler
        val throttle = RefreshThrottle()
        throttle.markRefreshed(now = 0L) // a manual pull just happened
        var refreshes = 0
        backgroundScope.launch {
            autoRefreshLoop(
                now = { scheduler.currentTime },
                isRefreshing = { false },
                gateOpen = { true },
                throttle = throttle,
                refresh = { refreshes++ },
                pollInterval = 10_000L,
            )
        }

        scheduler.runCurrent()                          // t=0, floor fresh from manual refresh → no leading edge
        assertEquals(0, refreshes)
        scheduler.advanceTimeBy(20_000); scheduler.runCurrent()   // t=20s, still within floor → skip
        assertEquals(0, refreshes)
        scheduler.advanceTimeBy(10_000); scheduler.runCurrent()   // t=30s, floor elapsed → fires
        assertEquals(1, refreshes)
    }
}

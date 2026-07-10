package eu.caiq.imagesorter.sync.ui.screens

import android.util.Log
import eu.caiq.imagesorter.sync.data.media.RefreshThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop

private const val TAG = "GOTODATE"

/**
 * Whether a periodic auto-refresh may run right now. Only refresh when the user is parked at
 * the top of the newest-first timeline (index 0) and not mid-interaction — refreshing while
 * scrolled down, seeking a date, previewing, or selecting would yank content out from under them.
 */
fun shouldAutoRefresh(
    firstVisibleItemIndex: Int,
    isSeekActive: Boolean,
    previewOpen: Boolean,
    selectionActive: Boolean,
): Boolean =
    firstVisibleItemIndex == 0 && !isSeekActive && !previewOpen && !selectionActive

/**
 * The extracted auto-refresh polling loop. It evaluates immediately (leading edge) before its
 * first [delay], so opening a screen whose [throttle] floor is already stale refreshes at once,
 * then re-evaluates every [pollInterval]. On each tick it refreshes only when the throttle floor
 * has elapsed, [gateOpen] is open, and no refresh is [isRefreshing]; a skipped tick is simply
 * re-checked next time (never queued or replayed). All collaborators are injected so a virtual
 * clock can drive it deterministically. Runs until the coroutine is cancelled.
 */
suspend fun autoRefreshLoop(
    now: () -> Long,
    isRefreshing: () -> Boolean,
    gateOpen: () -> Boolean,
    throttle: RefreshThrottle,
    refresh: suspend () -> Unit,
    pollInterval: Long = 30_000L,
) {
    while (true) {
        val canAuto = throttle.canAutoRefresh(now())
        val open = gateOpen()
        val refreshing = isRefreshing()
        Log.d(TAG, "autoRefreshLoop tick: canAutoRefresh=$canAuto gateOpen=$open isRefreshing=$refreshing")
        if (canAuto && open && !refreshing) {
            Log.d(TAG, "autoRefreshLoop: firing auto-refresh")
            throttle.markRefreshed(now())
            refresh()
        }
        delay(pollInterval)
    }
}

/**
 * An event-driven timeline refresh that **bypasses** the 30-second auto-refresh floor (unlike
 * the periodic [autoRefreshLoop]) but is still skipped while a refresh is already [isRefreshing].
 * It records the refresh on [throttle] so the periodic loop's leading edge does not immediately
 * fire a second, redundant refresh. Used when the Photos tab is opened.
 */
suspend fun refreshNow(
    now: () -> Long,
    isRefreshing: () -> Boolean,
    throttle: RefreshThrottle,
    refresh: suspend () -> Unit,
) {
    if (isRefreshing()) return
    throttle.markRefreshed(now())
    refresh()
}

/**
 * Refreshes the timeline each time the sync engine reports a completed batch (its session was
 * completed and outcomes reported), so newly synced items appear without user interaction. The
 * [completions] flow is a monotonically-changing signal; its current value on subscription is not
 * a completion, so the first emission is dropped. Each subsequent change triggers a [refreshNow]
 * (bypassing the floor, skipped while a refresh is in flight). Runs until the coroutine is cancelled.
 */
suspend fun syncCompletionRefreshLoop(
    completions: Flow<Int>,
    now: () -> Long,
    isRefreshing: () -> Boolean,
    throttle: RefreshThrottle,
    refresh: suspend () -> Unit,
) {
    completions.drop(1).collect {
        refreshNow(now, isRefreshing, throttle, refresh)
    }
}

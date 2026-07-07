package eu.caiq.imagesorter.sync.ui.screens

import eu.caiq.imagesorter.sync.data.media.RefreshThrottle
import kotlinx.coroutines.delay

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
        if (throttle.canAutoRefresh(now()) && gateOpen() && !isRefreshing()) {
            throttle.markRefreshed(now())
            refresh()
        }
        delay(pollInterval)
    }
}

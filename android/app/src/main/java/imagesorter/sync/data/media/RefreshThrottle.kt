package imagesorter.sync.data.media

/**
 * Enforces a minimum interval between auto-refreshes. The decision methods take an explicit
 * [now] (epoch millis) rather than reading the system clock, so the caller injects time and
 * the logic is unit-testable without a real clock. Owning this in a repository singleton lets
 * the floor survive tab switches and rotation.
 */
class RefreshThrottle(private val floorMs: Long = 30_000L) {

    private var lastRefreshedAt: Long? = null

    /** True if nothing has refreshed yet, or at least [floorMs] have elapsed since the last refresh. */
    fun canAutoRefresh(now: Long): Boolean {
        val last = lastRefreshedAt ?: return true
        return now - last >= floorMs
    }

    /** Records a refresh at [now], resetting the floor from this instant (also the manual-pull reset). */
    fun markRefreshed(now: Long) {
        lastRefreshedAt = now
    }
}

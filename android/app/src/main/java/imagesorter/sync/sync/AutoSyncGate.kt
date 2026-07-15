package imagesorter.sync.sync

/** How long after a successful full sync app-open auto-sync stays throttled (15 minutes). */
const val AUTO_SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L

/**
 * Whether the app should kick off a full sync on open. Only on an unmetered network, and only
 * when no successful full sync finished within the last [AUTO_SYNC_THROTTLE_MILLIS]; a null
 * [lastFullSyncAtMillis] (never synced) counts as eligible. Pure so the ViewModel can gate on it.
 */
fun shouldAutoSyncOnOpen(
    isUnmetered: Boolean,
    lastFullSyncAtMillis: Long?,
    nowMillis: Long,
): Boolean =
    isUnmetered &&
        (lastFullSyncAtMillis == null || nowMillis - lastFullSyncAtMillis >= AUTO_SYNC_THROTTLE_MILLIS)

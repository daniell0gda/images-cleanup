package eu.caiq.imagesorter.sync.sync

import android.content.Context

/**
 * The v1 trigger: a user-initiated sync that starts [SyncForegroundService] so
 * progress and failures stay visible via the ongoing notification.
 */
class ManualSyncTrigger(
    private val context: Context,
) : SyncTrigger {

    override fun requestSync() {
        SyncForegroundService.start(context)
    }
}

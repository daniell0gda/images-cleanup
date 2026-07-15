package imagesorter.sync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-registers the capture job after a reboot, since scheduled jobs do not
 * survive a device restart.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CaptureSyncScheduler.schedule(context)
        }
    }
}

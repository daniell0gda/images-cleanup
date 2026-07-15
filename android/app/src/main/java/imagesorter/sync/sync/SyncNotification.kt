package imagesorter.sync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import imagesorter.sync.R
import imagesorter.sync.ui.MainActivity

/**
 * The one "sync progress" ongoing notification, shared by [SyncForegroundService]
 * (foreground app-open / manual runs) and [CaptureSyncWorker] (background
 * after-capture runs) so both present an identical notification. Tapping it opens
 * the app on the Sync tab.
 */
object SyncNotification {

    const val CHANNEL_ID = "sync_progress"
    const val ID = 1001

    /** Build the ongoing notification; [max] > 0 renders a determinate progress bar. */
    fun build(context: Context, text: String, max: Int, progress: Int): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(syncTabIntent(context))
        if (max > 0) builder.setProgress(max, progress, false)
        return builder.build()
    }

    /** The notification body text for a given [progress] (phase or "Uploading X/Y"). */
    fun textFor(progress: SyncProgress): String =
        progress.message ?: when (progress.phase) {
            SyncPhase.UPLOADING -> "Uploading ${progress.completedFiles}/${progress.totalFiles}"
            else -> progress.phase.name.lowercase().replaceFirstChar { it.uppercase() }
        }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.sync_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /** Tapping the notification opens the app on the Sync tab. */
    private fun syncTabIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_HOME_TAB, MainActivity.EXTRA_HOME_TAB_SYNC)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}

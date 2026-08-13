package imagesorter.sync.sync

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import imagesorter.sync.SyncApp
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the incremental after-capture sync as an **expedited background** worker.
 *
 * Android 14 forbids starting a foreground service from the background (both a bare
 * JobService and WorkManager's setForeground hit ForegroundServiceStartNotAllowed),
 * so this deliberately does NOT go foreground. Expedited work gets a wakelock and
 * runs promptly in the background; a plain (non-foreground) notification — allowed
 * in the background with POST_NOTIFICATIONS — gives the run visibility and opens the
 * Sync tab when tapped. The run is bounded by the expedited window; the persisted
 * pending queue resumes anything left over on the next trigger or app open.
 */
class CaptureSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        fun show(text: String, max: Int, done: Int) =
            notifications.notify(SyncNotification.ID, SyncNotification.build(applicationContext, text, max, done))

        show(SyncNotification.textFor(SyncProgress()), 0, 0)
        val engine = (applicationContext as SyncApp).serviceLocator.syncEngine
        val progressJob = launch {
            engine.progress.collect { progress ->
                show(SyncNotification.textFor(progress), progress.totalFiles, progress.completedFiles)
            }
        }
        try {
            engine.run(incremental = true)
        } finally {
            // Join, not just cancel: a bare cancel() doesn't wait for a notify() call
            // already in flight on another thread, which could otherwise land after
            // the cancel(ID) below and leave the notification stuck with the worker done.
            progressJob.cancelAndJoin()
            notifications.cancel(SyncNotification.ID)
        }
        Result.success()
    }

    companion object {
        /** Unique-work name so overlapping capture triggers collapse to one run. */
        const val UNIQUE_WORK = "capture-sync"
    }
}

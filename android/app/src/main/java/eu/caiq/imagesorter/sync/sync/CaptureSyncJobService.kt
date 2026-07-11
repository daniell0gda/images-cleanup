package eu.caiq.imagesorter.sync.sync

import android.app.job.JobParameters
import android.app.job.JobService
import eu.caiq.imagesorter.sync.SyncApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fires when the camera writes new media (content-URI trigger registered by
 * [CaptureSyncScheduler]). Runs an **incremental sync in the job itself**: Android
 * 12+ forbids starting a foreground service from the background, so the capture
 * trigger cannot hand off to [SyncForegroundService] (that path is only reachable
 * from the foreground app-open/manual triggers). A JobService is allowed to do
 * network work in the background; the run is bounded by the job window and the
 * persisted pending queue resumes anything left over on the next trigger.
 *
 * The one-shot content trigger is re-armed for the next capture before the work
 * starts, and the shared engine's atomic guard makes this a no-op if a foreground
 * pass is already running.
 */
class CaptureSyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters?): Boolean {
        // Re-arm the one-shot content trigger so the next capture is observed.
        CaptureSyncScheduler.schedule(applicationContext)
        scope.launch {
            try {
                (application as SyncApp).serviceLocator.syncEngine.run(incremental = true)
            } finally {
                jobFinished(params, false)
            }
        }
        // Work continues on the coroutine; we call jobFinished() when it completes.
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Stopped early (window/constraints lost). The re-armed content trigger and
        // the resumable pending queue cover continuation, so don't hard-reschedule.
        scope.coroutineContext.cancel()
        return false
    }
}

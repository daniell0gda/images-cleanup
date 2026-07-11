package eu.caiq.imagesorter.sync.sync

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Fires when the camera writes new media (content-URI trigger from
 * [CaptureSyncScheduler]). It re-arms the one-shot trigger and hands the actual
 * upload to [CaptureSyncWorker] via WorkManager: a JobService may not start a
 * foreground service on Android 12+ (ForegroundServiceStartNotAllowedException),
 * but a WorkManager foreground worker may — so the upload runs reliably (unfrozen,
 * network unrestricted) with a visible notification.
 */
class CaptureSyncJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // Re-arm the one-shot content trigger for the next capture.
        CaptureSyncScheduler.schedule(applicationContext)
        val work = OneTimeWorkRequestBuilder<CaptureSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(CaptureSyncWorker.UNIQUE_WORK, ExistingWorkPolicy.KEEP, work)
        // Nothing runs on the job thread; WorkManager owns the run from here.
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

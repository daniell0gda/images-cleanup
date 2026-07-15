package imagesorter.sync.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore

/**
 * Schedules [CaptureSyncJobService] to fire when the device camera writes new
 * photos or videos. Content-trigger jobs are one-shot: they re-arm by scheduling
 * a fresh job each time they fire (and on app start / boot).
 */
object CaptureSyncScheduler {

    /** Stable id so a re-schedule replaces the existing pending job. */
    const val JOB_ID = 4201

    /**
     * (Re)register the capture job: content-URI triggers on the external images
     * and videos collections, gated to an unmetered network.
     */
    fun schedule(context: Context) {
        val component = ComponentName(context, CaptureSyncJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                ),
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                ),
            )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(jobInfo)
    }
}

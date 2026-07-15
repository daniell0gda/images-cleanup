package imagesorter.sync.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import imagesorter.sync.data.prefs.SyncNetworkType

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
     * and videos collections, gated to the network allowed by [networkType]
     * (unmetered for [SyncNetworkType.WIFI_ONLY], any connection for
     * [SyncNetworkType.ANY]). Re-scheduling with the same [JOB_ID] replaces the
     * pending job, so calling this after the user changes the setting re-arms the
     * trigger with the new constraint.
     */
    fun schedule(context: Context, networkType: SyncNetworkType) {
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
            .setRequiredNetworkType(jobNetworkType(networkType))
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(jobInfo)
    }

    /** Map the user's choice to the JobScheduler network constraint. */
    private fun jobNetworkType(networkType: SyncNetworkType): Int = when (networkType) {
        SyncNetworkType.WIFI_ONLY -> JobInfo.NETWORK_TYPE_UNMETERED
        SyncNetworkType.ANY -> JobInfo.NETWORK_TYPE_ANY
    }
}

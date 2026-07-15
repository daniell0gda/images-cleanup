package imagesorter.sync.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import imagesorter.sync.data.prefs.SyncNetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureSyncSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `schedules one job with image and video triggers and unmetered constraint for wifi only`() {
        CaptureSyncScheduler.schedule(context, SyncNetworkType.WIFI_ONLY)

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobs = scheduler.allPendingJobs
        assertEquals(1, jobs.size)
        val job = jobs.first()
        assertEquals(CaptureSyncScheduler.JOB_ID, job.id)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, job.networkType)

        val uris = job.triggerContentUris.orEmpty().map { it.uri }
        assertTrue(uris.contains(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        assertTrue(uris.contains(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
    }

    @Test
    fun `schedules with any-network constraint when set to any`() {
        CaptureSyncScheduler.schedule(context, SyncNetworkType.ANY)

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.allPendingJobs.first()
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
    }
}

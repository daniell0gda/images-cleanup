package eu.caiq.imagesorter.sync.sync

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureSyncJobServiceTest {

    @Test
    fun `onStartJob runs the sync in-job, re-arms the trigger, and starts no foreground service`() {
        val service = Robolectric.buildService(CaptureSyncJobService::class.java).create().get()

        val result = service.onStartJob(null)

        // Work continues on the job's coroutine; the framework is told so.
        assertTrue(result)

        val application: Application = ApplicationProvider.getApplicationContext()
        // Regression guard: Android 12+ throws ForegroundServiceStartNotAllowedException
        // if a background job starts a foreground service, so the capture job must NOT
        // start SyncForegroundService — it runs the engine in-job instead.
        assertNull(shadowOf(application).nextStartedService)

        // The one-shot content trigger is re-armed for the next capture.
        val scheduler = application.getSystemService(JobScheduler::class.java)
        val jobs = scheduler.allPendingJobs
        assertEquals(1, jobs.size)
        assertEquals(CaptureSyncScheduler.JOB_ID, jobs.first().id)
    }
}

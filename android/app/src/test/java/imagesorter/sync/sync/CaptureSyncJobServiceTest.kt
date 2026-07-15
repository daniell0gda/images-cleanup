package imagesorter.sync.sync

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureSyncJobServiceTest {

    @Before
    fun setUp() {
        // Enqueued work stays ENQUEUED (not executed) under the test harness, so the
        // worker's engine is never actually run here.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `onStartJob enqueues the capture worker, re-arms the trigger, and starts no foreground service`() {
        val service = Robolectric.buildService(CaptureSyncJobService::class.java).create().get()

        val result = service.onStartJob(null)

        // Handed off to WorkManager; nothing runs on the job thread.
        assertFalse(result)

        val application: Application = ApplicationProvider.getApplicationContext()
        // Regression guard: a background job must NOT start a foreground service
        // (Android 12+ throws ForegroundServiceStartNotAllowedException); the upload
        // is delegated to a WorkManager foreground worker instead.
        assertNull(shadowOf(application).nextStartedService)

        // The unique capture-sync work is enqueued.
        val work = WorkManager.getInstance(application)
            .getWorkInfosForUniqueWork(CaptureSyncWorker.UNIQUE_WORK).get()
        assertEquals(1, work.size)

        // The one-shot content trigger is re-armed for the next capture.
        val scheduler = application.getSystemService(JobScheduler::class.java)
        assertEquals(1, scheduler.allPendingJobs.size)
        assertEquals(CaptureSyncScheduler.JOB_ID, scheduler.allPendingJobs.first().id)
    }
}

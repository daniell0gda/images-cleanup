package imagesorter.sync.sync

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootCompletedReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `boot completed reschedules the capture job`() {
        BootCompletedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobs = scheduler.allPendingJobs
        assertEquals(1, jobs.size)
        assertEquals(CaptureSyncScheduler.JOB_ID, jobs.first().id)
    }
}

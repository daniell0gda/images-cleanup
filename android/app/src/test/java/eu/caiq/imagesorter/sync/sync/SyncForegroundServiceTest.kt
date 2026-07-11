package eu.caiq.imagesorter.sync.sync

import android.content.Intent
import eu.caiq.imagesorter.sync.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncForegroundServiceTest {

    @Test
    fun `incremental extra true maps to incremental run`() {
        val intent = Intent().putExtra(SyncForegroundService.EXTRA_INCREMENTAL, true)
        assertTrue(SyncForegroundService.isIncremental(intent))
    }

    @Test
    fun `missing extra maps to full run`() {
        assertFalse(SyncForegroundService.isIncremental(Intent()))
    }

    @Test
    fun `null intent maps to full run`() {
        assertFalse(SyncForegroundService.isIncremental(null))
    }

    @Test
    fun `ongoing notification opens MainActivity on the Sync tab`() {
        val service = Robolectric.buildService(SyncForegroundService::class.java).create().get()

        val notification = service.buildNotification("Idle", 0, 0)

        assertNotNull("ongoing notification must carry a content intent", notification.contentIntent)
        val launched = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertEquals(MainActivity.EXTRA_HOME_TAB_SYNC, launched.getStringExtra(MainActivity.EXTRA_HOME_TAB))
    }
}

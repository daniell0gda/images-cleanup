package imagesorter.sync.sync

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncNotificationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `audible build uses the normal channel`() {
        val notification = SyncNotification.build(context, "Idle", 0, 0, silent = false)

        assertEquals(SyncNotification.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun `silent build uses the silent channel`() {
        val notification = SyncNotification.build(context, "Idle", 0, 0, silent = true)

        assertEquals(SyncNotification.CHANNEL_ID_SILENT, notification.channelId)
    }

    @Test
    fun `silent channel is created without sound`() {
        SyncNotification.build(context, "Idle", 0, 0, silent = true)

        val channel = manager.getNotificationChannel(SyncNotification.CHANNEL_ID_SILENT)
        assertNotNull("silent channel must be created", channel)
        assertNull("silent channel must not play a sound", channel.sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }
}

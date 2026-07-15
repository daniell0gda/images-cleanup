package imagesorter.sync.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecurePrefsServerAddressTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun serverAddressIsNullByDefaultOnFreshInstall() {
        val prefs = SecurePrefs(context)
        assertNull(prefs.getServerAddress())
    }

    @Test
    fun serverAddressPersistsWithinInstanceAndAcrossNewInstance() {
        val prefs = SecurePrefs(context)
        prefs.setServerAddress("192.168.1.10:7000")

        assertEquals("192.168.1.10:7000", prefs.getServerAddress())

        val reopened = SecurePrefs(context)
        assertEquals("192.168.1.10:7000", reopened.getServerAddress())
    }
}

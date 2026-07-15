package imagesorter.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceLocatorApiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun apiConstructionDoesNotCrashWithoutStoredAddress() {
        val locator = ServiceLocator(context)
        // No address stored: building the client must not crash and must not
        // depend on any stored address.
        assertNotNull(locator.api)
    }

    @Test
    fun apiIsRebuiltWhenServerAddressChanges() {
        val locator = ServiceLocator(context)
        locator.securePrefs.setServerAddress("host-a:7000")
        val first = locator.api
        val firstAgain = locator.api
        assertSame(first, firstAgain)

        locator.securePrefs.setServerAddress("host-b:8000")
        val second = locator.api
        assertNotSame(first, second)
    }
}

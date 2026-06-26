package eu.caiq.imagesorter.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlTest {

    @Test
    fun storedAddressBecomesHttpBaseUrl() {
        assertEquals("http://192.168.1.10:7000/", serverAddressToBaseUrl("192.168.1.10:7000"))
    }

    @Test
    fun nullAddressDoesNotFallBackToHardcodedDefault() {
        val url = serverAddressToBaseUrl(null)
        assertNull(url)
    }

    @Test
    fun derivedUrlNeverTargetsHardcodedNasDefault() {
        assertEquals("http://my-nas:7000/", serverAddressToBaseUrl("my-nas:7000"))
        // The removed default must not be the source of truth.
        assertNull(serverAddressToBaseUrl(null))
    }
}

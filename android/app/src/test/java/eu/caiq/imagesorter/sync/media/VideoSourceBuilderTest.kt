package eu.caiq.imagesorter.sync.media

import androidx.media3.common.MediaItem
import eu.caiq.imagesorter.sync.data.media.MediaUrls
import eu.caiq.imagesorter.sync.data.media.buildVideoMediaItem
import eu.caiq.imagesorter.sync.data.media.videoRequestHeaders
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ExoPlayer wiring is asserted at the request-building level (URL + bearer
 * header), not via real playback — playback is device/codec behavior (§9 Notes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoSourceBuilderTest {

    private val urls = MediaUrls("http://host:7000")

    @Test
    fun mediaItemPointsAtTheStreamEndpoint() {
        val item: MediaItem = buildVideoMediaItem(urls, id = 9)
        assertEquals("http://host:7000/api/media/9/stream", item.localConfiguration?.uri.toString())
    }

    @Test
    fun requestHeadersCarryTheBearerToken() {
        assertEquals(mapOf("Authorization" to "Bearer tok-1"), videoRequestHeaders("tok-1"))
    }

    @Test
    fun requestHeadersEmptyWithoutToken() {
        assertEquals(emptyMap<String, String>(), videoRequestHeaders(null))
    }
}

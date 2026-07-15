package imagesorter.sync.media

import imagesorter.sync.data.media.MediaUrls
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlsTest {

    private val urls = MediaUrls("http://10.0.0.5:7000/")

    @Test
    fun thumbUrlTargetsThumbEndpoint() {
        assertEquals("http://10.0.0.5:7000/api/media/7/thumb", urls.thumb(7))
    }

    @Test
    fun previewUrlTargetsPreviewEndpoint() {
        assertEquals("http://10.0.0.5:7000/api/media/42/preview", urls.preview(42))
    }

    @Test
    fun streamUrlTargetsStreamEndpoint() {
        assertEquals("http://10.0.0.5:7000/api/media/9/stream", urls.stream(9))
    }

    @Test
    fun baseUrlWithoutTrailingSlashStillProducesSingleSlash() {
        val noSlash = MediaUrls("http://host:7000")
        assertEquals("http://host:7000/api/media/1/thumb", noSlash.thumb(1))
    }

    @Test
    fun bearerHeaderIsBuiltFromToken() {
        assertEquals(mapOf("Authorization" to "Bearer tok-1"), MediaUrls.authHeaders("tok-1"))
    }

    @Test
    fun bearerHeaderIsEmptyWhenNoToken() {
        assertEquals(emptyMap<String, String>(), MediaUrls.authHeaders(null))
        assertEquals(emptyMap<String, String>(), MediaUrls.authHeaders(""))
    }
}

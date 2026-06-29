package eu.caiq.imagesorter.sync.media

import eu.caiq.imagesorter.sync.support.FakeTokenStore
import eu.caiq.imagesorter.sync.support.buildMediaApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MediaApiWireTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun mediaParsesItemsAndNextCursorFromSnakeCaseResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":7,"kind":"image","date_taken":"2024-03-02T10:00:00",""" +
                    """"width":4000,"height":3000}],"next_cursor":"abc"}""",
            ),
        )
        val api = buildMediaApi(server)

        val page = api.media(cursor = null, limit = 100)

        val item = page.items.single()
        assertEquals(7L, item.id)
        assertEquals("image", item.kind)
        assertEquals("2024-03-02T10:00:00", item.dateTaken)
        assertEquals(4000, item.width)
        assertEquals(3000, item.height)
        assertEquals("abc", page.nextCursor)
    }

    @Test
    fun mediaFinalPageHasNullCursorAndPassesCursorAsQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":null}"""))
        val api = buildMediaApi(server, tokenStore = FakeTokenStore("tok-1"))

        val page = api.media(cursor = "opaque", limit = 50)

        assertNull(page.nextCursor)
        val request = server.takeRequest()
        assertEquals("/api/media?cursor=opaque&limit=50", request.path)
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
    }
}

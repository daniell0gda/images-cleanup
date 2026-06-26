package eu.caiq.imagesorter.sync.data.api

import eu.caiq.imagesorter.sync.support.buildSyncApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerProbeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun parsesHostAndPortIntoNormalizedBaseUrl() {
        val result = ServerProbe.parse("nas.local", "7000")
        assertTrue(result is ParseResult.Valid)
        assertEquals("http://nas.local:7000/", (result as ParseResult.Valid).baseUrl)
    }

    @Test
    fun rejectsBlankHostAsInvalid() {
        assertTrue(ServerProbe.parse("   ", "7000") is ParseResult.Invalid)
    }

    @Test
    fun rejectsNonNumericPortAsInvalid() {
        assertTrue(ServerProbe.parse("nas.local", "abc") is ParseResult.Invalid)
    }

    @Test
    fun rejectsOutOfRangePortAsInvalid() {
        assertTrue(ServerProbe.parse("nas.local", "70000") is ParseResult.Invalid)
        assertTrue(ServerProbe.parse("nas.local", "0") is ParseResult.Invalid)
    }

    @Test
    fun rejectsBlankPortAsInvalid() {
        assertTrue(ServerProbe.parse("nas.local", "") is ParseResult.Invalid)
    }

    @Test
    fun validateReturnsSuccessWhenServerResponds2xx() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val api = buildSyncApi(server)

        val result = ServerProbe.validate(api)

        assertTrue(result is ProbeResult.Success)
    }

    @Test
    fun validateReturnsBadResponseWhenServerRespondsNon2xx() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val api = buildSyncApi(server)

        val result = ServerProbe.validate(api)

        assertTrue(result is ProbeResult.BadResponse)
        assertEquals(500, (result as ProbeResult.BadResponse).code)
    }

    @Test
    fun validateReturnsUnreachableWhenConnectionFails() = runTest {
        val api = buildSyncApi(server)
        server.shutdown()

        val result = ServerProbe.validate(api)

        assertTrue(result is ProbeResult.Unreachable)
    }
}

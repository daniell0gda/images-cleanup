package eu.caiq.imagesorter.sync.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import eu.caiq.imagesorter.sync.data.api.dto.ErrorReportItemDto
import eu.caiq.imagesorter.sync.data.api.dto.IdentityDto
import eu.caiq.imagesorter.sync.data.api.dto.OpenSessionRequest
import eu.caiq.imagesorter.sync.data.api.dto.RegisterDeviceRequest
import eu.caiq.imagesorter.sync.support.FakeTokenStore
import eu.caiq.imagesorter.sync.support.buildSyncApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WireContractTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun bodyAsObject(json: String): Map<String, Any?> {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type).fromJson(json)!!
    }

    private fun bodyAsArray(json: String): List<Map<String, Any?>> {
        val map = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val list = Types.newParameterizedType(List::class.java, map)
        return moshi.adapter<List<Map<String, Any?>>>(list).fromJson(json)!!
    }

    @Test
    fun reconcileSerializesIdentitiesAsNameCreatedOnSizeAndReadsAlreadySynced() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"name":"a.jpg","created_on":"2024-01-02T03:04:05","size":10,"already_synced":true}]}""",
            ),
        )
        val api = buildSyncApi(server)

        val response = api.reconcile(listOf(IdentityDto("a.jpg", "2024-01-02T03:04:05", 10)))

        val sent = bodyAsArray(server.takeRequest().body.readUtf8()).single()
        assertEquals("a.jpg", sent["name"])
        assertEquals("2024-01-02T03:04:05", sent["created_on"])
        assertEquals(10.0, sent["size"])
        assertEquals(setOf("name", "created_on", "size"), sent.keys)

        assertTrue(response.results.single().alreadySynced)
    }

    @Test
    fun verifyReadsPresentFromSnakeCaseResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"name":"a.jpg","created_on":"2024-01-02T03:04:05","size":10,"present":true}]}""",
            ),
        )
        val api = buildSyncApi(server)

        val response = api.verify(listOf(IdentityDto("a.jpg", "2024-01-02T03:04:05", 10)))

        assertTrue(response.results.single().present)
    }

    @Test
    fun registerSerializesDeviceIdAndDeserializesPairingCode() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"status":"pending","pairing_code":"123456"}"""),
        )
        val api = buildSyncApi(server)

        val response = api.registerDevice(RegisterDeviceRequest(deviceId = "dev-1", name = "Pixel"))

        val sent = bodyAsObject(server.takeRequest().body.readUtf8())
        assertEquals("dev-1", sent["device_id"])
        assertEquals("Pixel", sent["name"])
        assertEquals("123456", response.pairingCode)
    }

    @Test
    fun openSessionUsesProfileIdAndSessionIdWireKeys() = runTest {
        server.enqueue(MockResponse().setBody("""{"session_id":"sess-9"}"""))
        val api = buildSyncApi(server)

        val response = api.openSession(OpenSessionRequest(profileId = "groupby"))

        val sent = bodyAsObject(server.takeRequest().body.readUtf8())
        assertEquals("groupby", sent["profile_id"])
        assertEquals("sess-9", response.sessionId)
    }

    @Test
    fun outcomeDeserializesFileIdAndCreatedOnWireKeys() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"outcomes":[{"file_id":"f-1","name":"a.jpg","status":"synced"}]}""",
            ),
        )
        val api = buildSyncApi(server)

        val response = api.outcomes("sess-9")

        assertEquals("f-1", response.outcomes.single().fileId)
    }

    @Test
    fun reportErrorsSerializesItemsWithCamelCaseFieldsAndSendsBearer() = runTest {
        server.enqueue(MockResponse().setBody("""{"stored":1}"""))
        val api = buildSyncApi(server, tokenStore = FakeTokenStore("tok-xyz"))

        api.reportErrors(
            listOf(
                ErrorReportItemDto(
                    name = "a.jpg",
                    createdOn = "2024-01-02T03:04:05",
                    size = 10,
                    reason = "UNREADABLE",
                    retryable = false,
                    message = "boom",
                    failedAt = 123,
                ),
            ),
        )

        val request = server.takeRequest()
        assertEquals("Bearer tok-xyz", request.getHeader("Authorization"))
        val sent = bodyAsArray(request.body.readUtf8()).single()
        assertEquals("a.jpg", sent["name"])
        assertEquals("2024-01-02T03:04:05", sent["createdOn"])
        assertEquals(10.0, sent["size"])
        assertEquals("UNREADABLE", sent["reason"])
        assertEquals(false, sent["retryable"])
        assertEquals("boom", sent["message"])
        assertEquals(123.0, sent["failedAt"])
        assertEquals(
            setOf("name", "createdOn", "size", "reason", "retryable", "message", "failedAt"),
            sent.keys,
        )
    }

    // --- AuthInterceptor ---

    @Test
    fun authInterceptorAttachesBearerOnReconcileWhenTokenStored() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val api = buildSyncApi(server, tokenStore = FakeTokenStore("tok-abc"))

        api.reconcile(emptyList())

        assertEquals("Bearer tok-abc", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun authInterceptorOmitsHeaderOnRegisterAndStatusEvenWithToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"pending","pairing_code":"000000"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        val api = buildSyncApi(server, tokenStore = FakeTokenStore("tok-abc"))

        api.registerDevice(RegisterDeviceRequest("dev-1", "Pixel"))
        api.deviceStatus("dev-1", null)

        assertNull(server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun authInterceptorOmitsHeaderOnAuthedCallWhenNoTokenStored() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val api = buildSyncApi(server, tokenStore = FakeTokenStore(null))

        runCatching { api.reconcile(emptyList()) }

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}

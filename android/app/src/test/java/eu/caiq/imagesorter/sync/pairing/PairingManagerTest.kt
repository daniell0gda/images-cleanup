package eu.caiq.imagesorter.sync.pairing

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import eu.caiq.imagesorter.sync.support.FakeCredentialStore
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

class PairingManagerTest {

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

    private fun manager(creds: FakeCredentialStore) =
        PairingManager(buildSyncApi(server), creds, deviceName = "Pixel")

    private fun sentBody(json: String): Map<String, Any?> {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type).fromJson(json)!!
    }

    @Test
    fun registerPostsDeviceIdAndNameAndReturnsCodeWithoutStoringToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"pending","pairing_code":"654321"}"""))
        val creds = FakeCredentialStore(deviceId = "dev-9")

        val code = manager(creds).register()

        val body = sentBody(server.takeRequest().body.readUtf8())
        assertEquals("dev-9", body["device_id"])
        assertEquals("Pixel", body["name"])
        assertEquals("654321", code)
        assertNull(creds.currentToken)
    }

    @Test
    fun pollStoresTokenAndResolvesTrustedAfterPending() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"trusted","token":"tok-xyz"}"""))
        val creds = FakeCredentialStore()

        val states = mutableListOf<PairingState>()
        val result = manager(creds).pollUntilResolved(intervalMillis = 0) { states += it }

        assertEquals(PairingState.Trusted, result)
        assertEquals("tok-xyz", creds.currentToken)
        assertTrue(states.first() is PairingState.Pending)
    }

    @Test
    fun pollResolvesRevokedAndClearsTokenWhenStatusRevoked() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"revoked"}"""))
        val creds = FakeCredentialStore(initialToken = "old-tok")

        val result = manager(creds).pollUntilResolved(intervalMillis = 0) {}

        assertEquals(PairingState.Revoked, result)
        assertNull(creds.currentToken)
        assertTrue(creds.repairCleared)
    }

    @Test
    fun pollResolvesRevokedWhenDeviceUnknown404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val creds = FakeCredentialStore(initialToken = "old-tok")

        val result = manager(creds).pollUntilResolved(intervalMillis = 0) {}

        assertEquals(PairingState.Revoked, result)
    }

    @Test
    fun checkStatusSendsStoredPairingCodeAsHeader() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        val creds = FakeCredentialStore().apply { setPairingCode("424242") }

        manager(creds).checkStatus()

        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/status"))
        assertEquals("424242", request.getHeader("X-Pairing-Code"))
    }

    @Test
    fun registerPersistsPairingCodeForLaterPolling() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"pending","pairing_code":"778899"}"""))
        val creds = FakeCredentialStore(deviceId = "dev-9")

        manager(creds).register()

        assertEquals("778899", creds.currentPairingCode)
    }

    @Test
    fun checkStatusWithoutStoredCodeDoesNotCrashAndReportsPending() = runTest {
        // No pairing code stored (e.g. app data cleared): status omits the token,
        // so the phone stays pending without crashing.
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        val creds = FakeCredentialStore()

        val state = manager(creds).checkStatus()

        assertTrue(state is PairingState.Pending)
        assertNull(server.takeRequest().getHeader("X-Pairing-Code"))
        assertNull(creds.currentToken)
    }

    @Test
    fun beginPairingRecoversTrustWithoutRegisteringWhenServerAlreadyTrusts() = runTest {
        // The server already trusts this device (the phone just lost its local
        // token). beginPairing must adopt the token and must NOT POST /devices,
        // which would reset the device back to pending.
        server.enqueue(MockResponse().setBody("""{"status":"trusted","token":"tok-xyz"}"""))
        val creds = FakeCredentialStore()

        val state = manager(creds).beginPairing()

        assertEquals(PairingState.Trusted, state)
        assertEquals("tok-xyz", creds.currentToken)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path!!.endsWith("/status"))
    }

    @Test
    fun beginPairingShowsExistingPendingCodeWithoutRegistering() = runTest {
        // A device already pending on the server returns its code via status, so
        // beginPairing surfaces it without re-registering (which mints a new code).
        server.enqueue(MockResponse().setBody("""{"status":"pending","pairing_code":"123456"}"""))

        val state = manager(FakeCredentialStore()).beginPairing()

        assertEquals(PairingState.Pending("123456"), state)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path!!.endsWith("/status"))
    }

    @Test
    fun beginPairingRegistersWhenServerDoesNotKnowDevice() = runTest {
        // Unknown device (404 on status) → register to obtain a fresh code.
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"status":"pending","pairing_code":"654321"}"""))

        val state = manager(FakeCredentialStore()).beginPairing()

        assertEquals(PairingState.Pending("654321"), state)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.endsWith("/status"))
        assertEquals("POST", server.takeRequest().method)
    }
}

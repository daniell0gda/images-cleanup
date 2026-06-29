package eu.caiq.imagesorter.sync.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.ServiceLocator
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.ui.screens.StatusFilter
import eu.caiq.imagesorter.sync.ui.screens.StatusRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/** In-memory [RoutingPrefs] so routing can be driven without EncryptedSharedPreferences. */
private class FakeRoutingPrefs(
    private var address: String? = null,
    private val trusted: Boolean = false,
    private val profileId: String? = null,
) : RoutingPrefs {
    override fun getServerAddress(): String? = address
    override fun setServerAddress(value: String?) { address = value }
    override fun isTrusted(): Boolean = trusted
    override fun getProfileId(): String? = profileId
}

/**
 * [SyncApi] stub whose only meaningful behaviour is the reachability probe.
 * A [code] of null simulates an unreachable server (IOException); a 2xx maps to
 * success, anything else to an HTTP error response.
 */
private class ProbeOnlyApi(private val code: Int?) : SyncApi by NotImplementedSyncApi() {
    override suspend fun ping(): Response<okhttp3.ResponseBody> =
        when {
            code == null -> throw java.io.IOException("unreachable")
            code in 200..299 -> Response.success("[]".toResponseBody())
            else -> Response.error(code, "".toResponseBody())
        }
}

/**
 * [SyncApi] stub whose [ping] suspends on a caller-controlled gate and counts how
 * many times it was entered — lets a test hold a probe "in flight" while asserting
 * the connecting state and the double-submit guard.
 */
private class GatedProbeApi : SyncApi by NotImplementedSyncApi() {
    var calls = 0
        private set
    private val gate = CompletableDeferred<Unit>()

    fun release() = gate.complete(Unit)

    override suspend fun ping(): Response<okhttp3.ResponseBody> {
        calls++
        gate.await()
        return Response.success("[]".toResponseBody())
    }
}

/** Throws for every [SyncApi] method; [ProbeOnlyApi] overrides only [ping]. */
private class NotImplementedSyncApi : SyncApi {
    private fun fail(): Nothing = throw UnsupportedOperationException("not used in this test")
    override suspend fun ping() = fail()
    override suspend fun registerDevice(body: eu.caiq.imagesorter.sync.data.api.dto.RegisterDeviceRequest) = fail()
    override suspend fun deviceStatus(deviceId: String) = fail()
    override suspend fun profiles() = fail()
    override suspend fun reconcile(identities: List<eu.caiq.imagesorter.sync.data.api.dto.IdentityDto>) = fail()
    override suspend fun verify(identities: List<eu.caiq.imagesorter.sync.data.api.dto.IdentityDto>) = fail()
    override suspend fun openSession(body: eu.caiq.imagesorter.sync.data.api.dto.OpenSessionRequest) = fail()
    override suspend fun fileOffset(sessionId: String, fileId: String) = fail()
    override suspend fun uploadChunk(
        sessionId: String,
        fileId: String,
        fileName: String,
        fileCreatedOn: String,
        fileSize: Long,
        fileMimeType: String,
        uploadOffset: Long,
        chunk: okhttp3.RequestBody,
    ) = fail()
    override suspend fun completeSession(sessionId: String) = fail()
    override suspend fun outcomes(sessionId: String) = fail()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vmWith(
        prefs: RoutingPrefs,
        apiFactory: (String) -> SyncApi = { ProbeOnlyApi(200) },
    ): MainViewModel = MainViewModel(ServiceLocator(context), prefs, apiFactory)

    @Test
    fun initialScreenIsServerSetupWhenNoAddressStored() {
        val vm = vmWith(FakeRoutingPrefs(address = null))
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }

    @Test
    fun initialScreenIsPairingWhenAddressStoredButNotTrusted() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = false))
        assertEquals(AppScreen.PAIRING, vm.screen.value)
    }

    @Test
    fun initialScreenIsProfilePickerWhenTrustedWithoutProfile() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = null))
        assertEquals(AppScreen.PROFILE_PICKER, vm.screen.value)
    }

    @Test
    fun initialScreenIsMainWhenTrustedWithProfile() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        assertEquals(AppScreen.MAIN, vm.screen.value)
    }

    @Test
    fun connectSuccessPersistsAddressRepointsApiAndAdvancesToPairing() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { ProbeOnlyApi(200) }

        vm.connect("nas.local", "7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("nas.local:7000", prefs.getServerAddress())
        assertEquals(AppScreen.PAIRING, vm.screen.value)
        assertNull(vm.serverSetupError.value)
    }

    @Test
    fun connectInvalidInputSurfacesErrorWithoutProbingOrAdvancing() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { throw IllegalStateException("must not probe on invalid input") }

        vm.connect("   ", "7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.serverSetupError.value)
        assertNull(prefs.getServerAddress())
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }

    @Test
    fun connectFailureSurfacesErrorDoesNotPersistAndStaysOnServerSetup() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { ProbeOnlyApi(500) }

        vm.connect("nas.local", "7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.serverSetupError.value)
        assertNull(prefs.getServerAddress())
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }

    @Test
    fun secondConnectWhileProbeInFlightDoesNotStartSecondProbe() = runTest(dispatcher) {
        val api = GatedProbeApi()
        val vm = vmWith(FakeRoutingPrefs(address = null)) { api }

        vm.connect("nas.local", "7000")
        vm.connect("nas.local", "7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, api.calls)
        api.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun connectingIsTrueDuringProbeAndFalseAfterItResolves() = runTest(dispatcher) {
        val api = GatedProbeApi()
        val vm = vmWith(FakeRoutingPrefs(address = null)) { api }

        vm.connect("nas.local", "7000")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.connecting.value)

        api.release()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.connecting.value)
    }

    @Test
    fun newValidConnectClearsStaleErrorWhileProbeRuns() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val gated = GatedProbeApi()
        var attempt = 0
        val vm = vmWith(prefs) { if (attempt++ == 0) ProbeOnlyApi(500) else gated }

        vm.connect("bad.host", "7000")
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.serverSetupError.value)

        vm.connect("good.host", "7000")
        dispatcher.scheduler.advanceUntilIdle()
        // The probe is still in flight (gated), yet the stale error is already cleared.
        assertNull(vm.serverSetupError.value)

        gated.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun applyFilterProjectsEachFilterToItsExpectedSlice() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        val all = listOf(
            StatusRow(name = "synced.jpg", status = SyncStatus.SYNCED),
            StatusRow(name = "pending.jpg", status = SyncStatus.PENDING),
            StatusRow(name = "failed.jpg", status = SyncStatus.FAILED, failureReason = "x"),
            StatusRow(name = "inprogress.jpg", status = SyncStatus.IN_PROGRESS),
            StatusRow(name = "notpeople.jpg", status = SyncStatus.UNCLASSIFIED),
        )

        fun names(f: StatusFilter) = vm.applyFilter(all, f).map { it.name }.toSet()

        // ALL includes UNCLASSIFIED.
        assertEquals(all.map { it.name }.toSet(), names(StatusFilter.ALL))
        // SYNCED_TODAY shows only SYNCED.
        assertEquals(setOf("synced.jpg"), names(StatusFilter.SYNCED_TODAY))
        // NOT_PEOPLE shows only UNCLASSIFIED.
        assertEquals(setOf("notpeople.jpg"), names(StatusFilter.NOT_PEOPLE))
        // WORKING_SET excludes UNCLASSIFIED and synced-without-failure.
        assertEquals(
            setOf("pending.jpg", "failed.jpg", "inprogress.jpg"),
            names(StatusFilter.WORKING_SET),
        )
    }

    @Test
    fun homeShellOpensOnPhotosTabByDefault() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        assertEquals(HomeTab.PHOTOS, vm.homeTab.value)
    }

    @Test
    fun selectHomeTabSwitchesBetweenPhotosAndSync() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        vm.selectHomeTab(HomeTab.SYNC)
        assertEquals(HomeTab.SYNC, vm.homeTab.value)
        vm.selectHomeTab(HomeTab.PHOTOS)
        assertEquals(HomeTab.PHOTOS, vm.homeTab.value)
    }

    @Test
    fun connectUnreachableSurfacesErrorAndStaysOnServerSetup() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { ProbeOnlyApi(null) }

        vm.connect("nas.local", "7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.serverSetupError.value)
        assertNull(prefs.getServerAddress())
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }
}

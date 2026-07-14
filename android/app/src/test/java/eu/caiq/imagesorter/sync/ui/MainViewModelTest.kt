package eu.caiq.imagesorter.sync.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.ServiceLocator
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.sync.SyncTrigger
import eu.caiq.imagesorter.sync.ui.screens.StatusFilter
import eu.caiq.imagesorter.sync.ui.screens.StatusRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/** Records [requestSync] calls so the auto-sync gate + manual bypass are assertable. */
private class FakeSyncTrigger : SyncTrigger {
    var calls = 0
        private set

    override fun requestSync() {
        calls++
    }
}

/** In-memory [RoutingPrefs] so routing can be driven without EncryptedSharedPreferences. */
private class FakeRoutingPrefs(
    private var address: String? = null,
    private val trusted: Boolean = false,
    private var profileId: String? = null,
) : RoutingPrefs {
    override fun getServerAddress(): String? = address
    override fun setServerAddress(value: String?) { address = value }
    override fun isTrusted(): Boolean = trusted
    override fun getProfileId(): String? = profileId
    override fun setProfileId(value: String) { profileId = value }
}

/**
 * [SyncApi] stub for profile create/list flows: [list] backs `profiles()`, while
 * `createProfile` either returns [created] or throws an [errorCode] HttpException.
 */
private class FakeProfileApi(
    private val list: List<eu.caiq.imagesorter.sync.data.api.dto.ProfileDto> = emptyList(),
    private val created: eu.caiq.imagesorter.sync.data.api.dto.ProfileDto? = null,
    private val errorCode: Int? = null,
) : SyncApi by NotImplementedSyncApi() {
    override suspend fun profiles() = list
    override suspend fun createProfile(
        body: eu.caiq.imagesorter.sync.data.api.dto.ProfileRequest,
    ): eu.caiq.imagesorter.sync.data.api.dto.ProfileDto {
        errorCode?.let {
            throw retrofit2.HttpException(Response.error<Any>(it, "".toResponseBody()))
        }
        return created ?: eu.caiq.imagesorter.sync.data.api.dto.ProfileDto(body.name, body.name)
    }
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
    override suspend fun deviceStatus(deviceId: String, pairingCode: String?) = fail()
    override suspend fun profiles() = fail()
    override suspend fun createProfile(body: eu.caiq.imagesorter.sync.data.api.dto.ProfileRequest) = fail()
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
    override suspend fun reportErrors(items: List<eu.caiq.imagesorter.sync.data.api.dto.ErrorReportItemDto>) = fail()
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

        vm.connect("nas.local:7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("nas.local:7000", prefs.getServerAddress())
        assertEquals(AppScreen.PAIRING, vm.screen.value)
        assertNull(vm.serverSetupError.value)
    }

    @Test
    fun connectInvalidInputSurfacesErrorWithoutProbingOrAdvancing() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { throw IllegalStateException("must not probe on invalid input") }

        vm.connect("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.serverSetupError.value)
        assertNull(prefs.getServerAddress())
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }

    @Test
    fun connectFailureSurfacesErrorDoesNotPersistAndStaysOnServerSetup() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { ProbeOnlyApi(500) }

        vm.connect("nas.local:7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.serverSetupError.value)
        assertNull(prefs.getServerAddress())
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }

    @Test
    fun secondConnectWhileProbeInFlightDoesNotStartSecondProbe() = runTest(dispatcher) {
        val api = GatedProbeApi()
        val vm = vmWith(FakeRoutingPrefs(address = null)) { api }

        vm.connect("nas.local:7000")
        vm.connect("nas.local:7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, api.calls)
        api.release()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun connectingIsTrueDuringProbeAndFalseAfterItResolves() = runTest(dispatcher) {
        val api = GatedProbeApi()
        val vm = vmWith(FakeRoutingPrefs(address = null)) { api }

        vm.connect("nas.local:7000")
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

        vm.connect("bad.host:7000")
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.serverSetupError.value)

        vm.connect("good.host:7000")
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
    fun cancellingSystemDeleteClearsTheQueueWithoutPruning() = runTest(dispatcher) {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        vm.requestNotPeopleDelete(
            listOf(StatusRow(name = "p.jpg", status = SyncStatus.PENDING, mediaStoreId = 5, mimeType = "image/jpeg")),
        )
        assertEquals(1, vm.notPeopleDeleteIds.value.size)

        // Cancel (RESULT_OK == false): the queue must clear so the launch effect
        // doesn't re-fire, and no prune coroutine is started.
        vm.onNotPeopleDeleteFinished(deleted = false)

        assertTrue(vm.notPeopleDeleteIds.value.isEmpty())
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
    fun goToAlbumSwitchesToAlbumsTabAndMarksTheAlbumPending() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))

        vm.goToAlbum(42)

        assertEquals(HomeTab.ALBUMS, vm.homeTab.value)
        assertEquals(42L, vm.pendingAlbumId.value)
    }

    @Test
    fun consumePendingAlbumClearsTheRequestSoItOpensOnlyOnce() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        vm.goToAlbum(42)

        vm.consumePendingAlbum()

        assertNull(vm.pendingAlbumId.value)
    }

    @Test
    fun goToSyncTabSwitchesToSyncTabAndMarksItPending() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))

        vm.goToSyncTab()

        assertEquals(HomeTab.SYNC, vm.homeTab.value)
        assertEquals(HomeTab.SYNC, vm.pendingTab.value)
    }

    @Test
    fun consumePendingTabClearsTheRequestSoItFiresOnlyOnce() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))
        vm.goToSyncTab()

        vm.consumePendingTab()

        assertNull(vm.pendingTab.value)
    }

    @Test
    fun homeTabFromIntentMapsSyncExtraToSyncTab() {
        val intent = android.content.Intent()
            .putExtra(MainActivity.EXTRA_HOME_TAB, MainActivity.EXTRA_HOME_TAB_SYNC)
        assertEquals(HomeTab.SYNC, MainActivity.homeTabFromIntent(intent))
    }

    @Test
    fun homeTabFromIntentIsNullWithoutTheExtraOrForNullIntent() {
        assertNull(MainActivity.homeTabFromIntent(android.content.Intent()))
        assertNull(MainActivity.homeTabFromIntent(null))
    }

    private fun profile(id: String) =
        eu.caiq.imagesorter.sync.data.api.dto.ProfileDto(profileId = id, displayName = id)

    private fun vmWithApi(prefs: RoutingPrefs, api: SyncApi): MainViewModel =
        MainViewModel(ServiceLocator(context), prefs, apiProvider = { api })

    @Test
    fun choosingProfileStoresIdAndAdvancesToMain() {
        val prefs = FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = null)
        val vm = vmWith(prefs)

        vm.chooseProfile(profile("Beach"))

        assertEquals("Beach", prefs.getProfileId())
        assertEquals(AppScreen.MAIN, vm.screen.value)
    }

    @Test
    fun createProfileSuccessAutoSelectsAndAdvancesWithoutASecondTap() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = null)
        val vm = vmWithApi(prefs, FakeProfileApi(created = profile("Beach")))

        vm.createProfile("beach")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Beach", prefs.getProfileId())
        assertEquals(AppScreen.MAIN, vm.screen.value)
        assertNull(vm.createProfileError.value)
    }

    @Test
    fun createProfileDuplicateSurfaces409MessageAndStaysOnPicker() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = null)
        val vm = vmWithApi(prefs, FakeProfileApi(errorCode = 409))

        vm.createProfile("Existing")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.createProfileError.value)
        assertNull(prefs.getProfileId())
        // No advance: the name can be corrected on the picker.
        assertNotEquals(AppScreen.MAIN, vm.screen.value)
    }

    @Test
    fun profileErrorMessageIsDistinctAndHumanReadableForDuplicateVsInvalid() {
        val vm = vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = null))

        val duplicate = vm.profileErrorMessage(409)
        val invalid = vm.profileErrorMessage(400)

        assertNotEquals(duplicate, invalid)
        assertFalse("409 message must not be the generic server-error text", duplicate.startsWith("Server error"))
        assertFalse("400 message must not be the generic server-error text", invalid.startsWith("Server error"))
    }

    @Test
    fun syncProfileRemovedRoutesBackToPickerWithNotice() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "Beach")
        val vm = vmWithApi(prefs, FakeProfileApi(list = emptyList()))

        vm.onSyncProfileRemoved()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppScreen.PROFILE_PICKER, vm.screen.value)
        assertNotNull(vm.profileNotice.value)
    }

    @Test
    fun exposesNoProfileDeleteOrRenameSurface() {
        val forbidden = listOf("delete", "rename")
        val names = (SyncApi::class.java.methods.map { it.name } +
            MainViewModel::class.java.methods.map { it.name })
        names.forEach { name ->
            val lower = name.lowercase()
            assertFalse(
                "unexpected profile delete/rename surface: $name",
                lower.contains("profile") && forbidden.any { lower.contains(it) },
            )
        }
    }

    private fun mainVm(): MainViewModel =
        vmWith(FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"))

    private fun row(id: Long, status: SyncStatus = SyncStatus.PENDING) =
        StatusRow(name = "f$id.jpg", status = status, mediaStoreId = id, mimeType = "image/jpeg")

    @Test
    fun resolveMediaItemRebuildsFromPendingQueueByMediaStoreId() = runBlocking {
        val vm = mainVm()
        val locator = ServiceLocator(context)
        locator.pendingUploadDao().deleteByMediaStoreIds(listOf(4001L))
        locator.pendingUploadDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity(
                fileId = "fid-4001",
                mediaStoreId = 4001L,
                name = "pend.jpg",
                createdOn = "2024-02-02T00:00:00",
                size = 12,
                mimeType = "image/jpeg",
                status = "PENDING",
                sortKey = 0,
            ),
        )

        val item = vm.resolveMediaItem(4001L)

        assertNotNull(item)
        assertEquals(4001L, item!!.mediaStoreId)
        assertEquals("pend.jpg", item.identity.name)
        assertEquals("2024-02-02T00:00:00", item.identity.createdOn)
        assertEquals(12L, item.identity.size)
        assertTrue(item.uri.toString().endsWith("/4001"))
        locator.pendingUploadDao().deleteByMediaStoreIds(listOf(4001L))
    }

    @Test
    fun resolveMediaItemRebuildsFailedRowFromSyncedCacheByMediaStoreId() = runBlocking {
        val vm = mainVm()
        val locator = ServiceLocator(context)
        locator.syncedCacheDao().deleteByMediaStoreIds(listOf(4002L))
        locator.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = "failed.jpg",
                createdOn = "2024-03-03T00:00:00",
                size = 34,
                status = "FAILED",
                mediaStoreId = 4002L,
                mimeType = "image/jpeg",
            ),
        )

        val item = vm.resolveMediaItem(4002L)

        assertNotNull(item)
        assertEquals(4002L, item!!.mediaStoreId)
        assertEquals("failed.jpg", item.identity.name)
        assertEquals(34L, item.identity.size)
        locator.syncedCacheDao().deleteByMediaStoreIds(listOf(4002L))
    }

    @Test
    fun syncItemNowAddsTheItemToInFlightImmediately() = runTest(dispatcher) {
        val vm = mainVm()
        assertTrue(vm.syncingNow.value.isEmpty())

        // An id with no backing cache row resolves to null, so no engine/network work
        // runs; the synchronous in-flight add is what this pins.
        vm.syncItemNow(row(id = 7771))

        assertTrue("tapped item is in flight at once", vm.syncingNow.value.contains(7771L))
    }

    @Test
    fun syncItemNowWithoutMediaStoreIdIsANoOp() {
        val vm = mainVm()
        vm.syncItemNow(StatusRow(name = "x.jpg", status = SyncStatus.PENDING, mediaStoreId = null))
        assertTrue(vm.syncingNow.value.isEmpty())
    }

    @Test
    fun syncItemNowForSeveralRowsEnqueuesEachWithoutDropping() = runTest(dispatcher) {
        val vm = mainVm()
        vm.syncItemNow(row(id = 7781))
        vm.syncItemNow(row(id = 7782))
        vm.syncItemNow(row(id = 7783))

        assertEquals(setOf(7781L, 7782L, 7783L), vm.syncingNow.value)
    }

    @Test
    fun inFlightItemClearsOnceItsRowIsSynced() {
        val vm = mainVm()
        // Seen active (PENDING) → stays in flight; then SYNCED outcome → drops.
        val (active, activated) = vm.nextSyncing(setOf(50L), emptySet(), listOf(row(50L, SyncStatus.PENDING)))
        assertEquals(setOf(50L), active)

        val (afterSync, _) = vm.nextSyncing(active, activated, listOf(row(50L, SyncStatus.SYNCED)))
        assertTrue("synced item leaves the in-flight set", afterSync.isEmpty())
    }

    @Test
    fun inFlightItemStaysDisabledAcrossPendingThenInProgressThenClearsOnSynced() {
        val vm = mainVm()
        // Tap: the row is PENDING → stays in flight (button disabled), marked activated.
        val (afterTap, act1) = vm.nextSyncing(setOf(70L), emptySet(), listOf(row(70L, SyncStatus.PENDING)))
        assertEquals("PENDING keeps the button disabled", setOf(70L), afterTap)

        // The (force_place or split) session picks it up: IN_PROGRESS → still in flight.
        val (uploading, act2) = vm.nextSyncing(afterTap, act1, listOf(row(70L, SyncStatus.IN_PROGRESS)))
        assertEquals("IN_PROGRESS keeps the button disabled", setOf(70L), uploading)

        // It finishes: SYNCED → leaves the in-flight set so the button resolves.
        val (afterSync, _) = vm.nextSyncing(uploading, act2, listOf(row(70L, SyncStatus.SYNCED)))
        assertTrue("SYNCED re-resolves the button (no longer syncing)", afterSync.isEmpty())
    }

    @Test
    fun dedupedTappedItemLeavesInFlightWhenTheLiveSessionReportsItsOutcome() {
        // Criterion: under the split, a Sync Now tap can dedupe onto an item the LIVE
        // session is already uploading — so its terminal state is reported by the live
        // session, not the force_place one. At tap the row is already IN_PROGRESS.
        val vm = mainVm()
        val (afterTap, act1) =
            vm.nextSyncing(setOf(80L), emptySet(), listOf(row(80L, SyncStatus.IN_PROGRESS)))
        assertEquals("deduped-onto-live item is in flight and disabled", setOf(80L), afterTap)

        // The live session completes the upload → the row flips to SYNCED and the id
        // must leave syncingNow, so the button is never stuck disabled.
        val (afterSync, _) = vm.nextSyncing(afterTap, act1, listOf(row(80L, SyncStatus.SYNCED)))
        assertTrue("live-session outcome resolves the button", afterSync.isEmpty())
    }

    @Test
    fun dedupedTappedItemReEnablesWhenTheLiveSessionReportsAFailure() {
        // The same dedupe case, but the live session fails the item: because it was seen
        // active (IN_PROGRESS) since the tap, a fresh FAILED drops the id → button re-enables.
        val vm = mainVm()
        val (afterTap, act1) =
            vm.nextSyncing(setOf(90L), emptySet(), listOf(row(90L, SyncStatus.IN_PROGRESS)))
        assertEquals(setOf(90L), afterTap)

        val (afterFail, _) = vm.nextSyncing(afterTap, act1, listOf(row(90L, SyncStatus.FAILED)))
        assertTrue("a live-session failure resolves and re-enables the button", afterFail.isEmpty())
    }

    @Test
    fun inFlightItemStaysForInitialFailedButClearsOnAFreshFailure() {
        val vm = mainVm()
        // Retry of a FAILED row: at tap the row is still FAILED (never seen active) → keep.
        val (afterTap, activated) = vm.nextSyncing(setOf(60L), emptySet(), listOf(row(60L, SyncStatus.FAILED)))
        assertEquals("initial FAILED state keeps the button in-progress", setOf(60L), afterTap)

        // Engine picks it up (IN_PROGRESS), then it fails again → drop so the button re-enables.
        val (active, act2) = vm.nextSyncing(afterTap, activated, listOf(row(60L, SyncStatus.IN_PROGRESS)))
        assertEquals(setOf(60L), active)
        val (afterFail, _) = vm.nextSyncing(active, act2, listOf(row(60L, SyncStatus.FAILED)))
        assertTrue("a fresh failure re-enables the button", afterFail.isEmpty())
    }

    private fun vmForAutoSync(
        trigger: SyncTrigger,
        lastFullSyncAtMillis: Long? = null,
    ): MainViewModel = MainViewModel(
        ServiceLocator(context),
        FakeRoutingPrefs(address = "nas.local:7000", trusted = true, profileId = "groupby"),
        syncTrigger = trigger,
        lastFullSyncAtMillis = { lastFullSyncAtMillis },
    )

    @Test
    fun maybeAutoSyncOnOpenStartsFullSyncWhenGatePasses() {
        val trigger = FakeSyncTrigger()
        // Unmetered + no prior full run → gate passes.
        val vm = vmForAutoSync(trigger, lastFullSyncAtMillis = null)

        vm.maybeAutoSyncOnOpen(isUnmetered = true)

        assertEquals(1, trigger.calls)
    }

    @Test
    fun maybeAutoSyncOnOpenStartsFullSyncWhenLastRunIsOldEnough() {
        val trigger = FakeSyncTrigger()
        // A full run 20 minutes ago is past the throttle window → gate passes.
        val vm = vmForAutoSync(trigger, lastFullSyncAtMillis = System.currentTimeMillis() - 20 * 60 * 1000L)

        vm.maybeAutoSyncOnOpen(isUnmetered = true)

        assertEquals(1, trigger.calls)
    }

    @Test
    fun maybeAutoSyncOnOpenDoesNotStartSyncOnMeteredNetwork() {
        val trigger = FakeSyncTrigger()
        val vm = vmForAutoSync(trigger, lastFullSyncAtMillis = null)

        vm.maybeAutoSyncOnOpen(isUnmetered = false)

        assertEquals(0, trigger.calls)
    }

    @Test
    fun maybeAutoSyncOnOpenDoesNotStartSyncWhenAFullRunFinishedRecently() {
        val trigger = FakeSyncTrigger()
        // A full run 1 minute ago is inside the 15-minute throttle window → gate fails.
        val vm = vmForAutoSync(trigger, lastFullSyncAtMillis = System.currentTimeMillis() - 60 * 1000L)

        vm.maybeAutoSyncOnOpen(isUnmetered = true)

        assertEquals(0, trigger.calls)
    }

    @Test
    fun syncNowStartsSyncUnconditionallyBypassingTheGate() {
        val trigger = FakeSyncTrigger()
        // Metered would fail the gate and a recent run would throttle it, yet the
        // manual "Back up now" path must start regardless.
        val vm = vmForAutoSync(trigger, lastFullSyncAtMillis = System.currentTimeMillis() - 60 * 1000L)

        vm.syncNow()

        assertEquals(1, trigger.calls)
    }

    @Test
    fun connectUnreachableSurfacesErrorAndStaysOnServerSetup() = runTest(dispatcher) {
        val prefs = FakeRoutingPrefs(address = null)
        val vm = vmWith(prefs) { ProbeOnlyApi(null) }

        vm.connect("nas.local:7000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.serverSetupError.value)
        assertNull(prefs.getServerAddress())
        assertEquals(AppScreen.SERVER_SETUP, vm.screen.value)
    }
}

package imagesorter.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import imagesorter.sync.ServiceLocator
import imagesorter.sync.data.api.ParseResult
import imagesorter.sync.data.api.ProbeResult
import imagesorter.sync.data.api.ServerProbe
import imagesorter.sync.data.api.dto.ProfileDto
import imagesorter.sync.data.db.entity.FailureEntity
import imagesorter.sync.data.db.entity.PendingUploadEntity
import imagesorter.sync.data.db.entity.SyncedCacheEntity
import imagesorter.sync.domain.model.FailureReason
import imagesorter.sync.domain.model.Identity
import imagesorter.sync.domain.model.MediaItem
import imagesorter.sync.domain.model.SyncStatus
import imagesorter.sync.pairing.PairingState
import imagesorter.sync.ui.screens.CleanupPhase
import imagesorter.sync.ui.screens.FailureDetail
import imagesorter.sync.ui.screens.StatusFilter
import imagesorter.sync.ui.screens.StatusRow
import imagesorter.sync.ui.screens.StatusTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Top-level screen the Activity should render. */
enum class AppScreen { SERVER_SETUP, PAIRING, PROFILE_PICKER, MAIN, CLEANUP }

/**
 * The bottom-navigation destination shown inside the post-pairing home shell.
 * [PHOTOS] browses the server media gallery (profile-independent); [SYNC] owns the
 * existing pairing/profile/back-up flow as a sub-state.
 */
enum class HomeTab { PHOTOS, ALBUMS, SYNC, SETTINGS }

/**
 * Narrow read/write seam over the prefs the routing + connect flow touches. Kept
 * separate from [ServiceLocator] so the routing logic is unit-testable with an
 * in-memory fake (the real [imagesorter.sync.data.prefs.SecurePrefs]
 * uses EncryptedSharedPreferences, which does not run under the JVM test harness).
 */
interface RoutingPrefs {
    fun getServerAddress(): String?
    fun setServerAddress(value: String?)
    fun isTrusted(): Boolean
    fun getProfileId(): String?
    fun setProfileId(value: String)
}

/**
 * Drives screen routing and the async flows (pairing poll, profile load, sync
 * trigger, cleanup verify). Holds no Android UI types so it stays testable; the
 * Activity owns permission requests and the system delete-dialog launch.
 *
 * This is a functional skeleton: routing logic is real, the visual layer is
 * placeholder. A production app would likely split these concerns further.
 */
class MainViewModel(
    private val locator: ServiceLocator,
    private val routingPrefs: RoutingPrefs = locator.securePrefs,
    private val apiFactory: (String) -> imagesorter.sync.data.api.SyncApi = locator::buildApi,
    // Supplier (not a captured instance) so a re-pointed server address is honored:
    // locator.api rebuilds when the address changes.
    private val apiProvider: () -> imagesorter.sync.data.api.SyncApi = { locator.api },
    private val syncTrigger: imagesorter.sync.sync.SyncTrigger = locator.syncTrigger,
    // Wall-clock timestamp of the last successful full run (persisted), or null if
    // none has completed. Read as a supplier so the throttle sees the current value.
    private val lastFullSyncAtMillis: () -> Long? = { locator.securePrefs.getLastFullSyncAtMillis() },
) : ViewModel() {

    init {
        // Self-healing route-back: when a sync run reports the chosen profile was
        // removed server-side (session-open 404), return to the picker with a notice.
        viewModelScope.launch {
            locator.syncEngine.progress.collect { progress ->
                if (progress.phase == imagesorter.sync.sync.SyncPhase.ERROR &&
                    progress.message == imagesorter.sync.sync.SyncEngine.PROFILE_REMOVED_MESSAGE
                ) {
                    onSyncProfileRemoved()
                }
            }
        }
    }

    private val _screen = MutableStateFlow(initialScreen())
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _homeTab = MutableStateFlow(HomeTab.PHOTOS)

    /** Selected bottom-nav destination in the post-pairing home shell; opens on Photos. */
    val homeTab: StateFlow<HomeTab> = _homeTab.asStateFlow()

    /** Switch the home shell's bottom-nav tab (Photos | Albums | Sync | Settings). */
    fun selectHomeTab(tab: HomeTab) {
        // Tapping any bottom-nav destination (including re-tapping Sync) exits the
        // Sync tab's cleanup sub-state, so "Free up space" is never a dead end — the
        // Sync tab always returns to its main status view.
        if (_screen.value == AppScreen.CLEANUP) _screen.value = AppScreen.MAIN
        _homeTab.value = tab
    }

    private val _syncNetworkType =
        MutableStateFlow(locator.securePrefs.getSyncNetworkType())

    /** Networks automatic sync may use; shown and edited on the Settings tab. */
    val syncNetworkType: StateFlow<imagesorter.sync.data.prefs.SyncNetworkType> =
        _syncNetworkType.asStateFlow()

    /**
     * Persist the sync-network choice and immediately re-arm the capture job so its
     * network constraint matches — otherwise the change would only take effect after
     * the next app start or reboot.
     */
    fun setSyncNetworkType(type: imagesorter.sync.data.prefs.SyncNetworkType) {
        locator.securePrefs.setSyncNetworkType(type)
        locator.rescheduleCaptureSync()
        _syncNetworkType.value = type
    }

    private val _pendingAlbumId = MutableStateFlow<Long?>(null)

    /** Album the Albums tab should open on entry (e.g. one just created on Photos), or null. */
    val pendingAlbumId: StateFlow<Long?> = _pendingAlbumId.asStateFlow()

    /** Navigate to a freshly created album: switch to the Albums tab and open its detail. */
    fun goToAlbum(albumId: Long) {
        _pendingAlbumId.value = albumId
        _homeTab.value = HomeTab.ALBUMS
    }

    /** Clear the pending album once the Albums tab has opened it, so it opens only once. */
    fun consumePendingAlbum() {
        _pendingAlbumId.value = null
    }

    private val _pendingTab = MutableStateFlow<HomeTab?>(null)

    /** Tab a deep link (e.g. the sync notification) requested opening, or null. */
    val pendingTab: StateFlow<HomeTab?> = _pendingTab.asStateFlow()

    /** Navigate to the Sync tab (e.g. from the ongoing sync notification). */
    fun goToSyncTab() {
        _pendingTab.value = HomeTab.SYNC
        selectHomeTab(HomeTab.SYNC)
    }

    /** Clear the pending tab once handled, so a recomposition doesn't re-navigate. */
    fun consumePendingTab() {
        _pendingTab.value = null
    }

    private val _serverSetupError = MutableStateFlow<String?>(null)

    /** Error message for the server-setup screen, or null when there is none. */
    val serverSetupError: StateFlow<String?> = _serverSetupError.asStateFlow()

    private val _connecting = MutableStateFlow(false)

    /** True while a reachability probe is in flight; gates re-entrant connect calls. */
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Pending(""))
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileDto>>(emptyList())
    val profiles: StateFlow<List<ProfileDto>> = _profiles.asStateFlow()

    private val _createProfileError = MutableStateFlow<String?>(null)

    /** Validation message for a failed profile creation, or null when there is none. */
    val createProfileError: StateFlow<String?> = _createProfileError.asStateFlow()

    private val _profileNotice = MutableStateFlow<String?>(null)

    /** One-off banner on the picker (e.g. the chosen profile was removed), or null. */
    val profileNotice: StateFlow<String?> = _profileNotice.asStateFlow()

    private val _filter = MutableStateFlow(StatusFilter.WORKING_SET)
    val filter: StateFlow<StatusFilter> = _filter.asStateFlow()

    // True while a working-set scan is running, starting true so the very first open
    // (before the initial discover() finishes) reads as "scanning" rather than an empty
    // working set. Lets the main view tell "still finding photos" apart from "nothing to
    // back up" instead of showing a misleading empty state during the scan.
    private val _discovering = MutableStateFlow(true)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /**
     * Live sync progress. The engine is a process-wide singleton, so this is the
     * same flow the foreground service drives — the UI mirrors the running pass.
     */
    val syncProgress: StateFlow<imagesorter.sync.sync.SyncProgress> =
        locator.syncEngine.progress

    private val _syncingNow = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * MediaStore ids currently being force-synced via [syncItemNow]. An id joins the
     * moment the action is called and leaves once the engine reports its outcome, so
     * the per-item "Sync Now" button's disabled/in-progress state is driven by real state.
     */
    val syncingNow: StateFlow<Set<Long>> = _syncingNow.asStateFlow()

    // Ids seen in an active (PENDING/IN_PROGRESS) row since their tap, so a fresh FAILED
    // outcome (re-enable the button) is told apart from the FAILED state a retry began from.
    private var syncingActivated: Set<Long> = emptySet()

    private val _cleanupPhase = MutableStateFlow(CleanupPhase.IDLE)
    val cleanupPhase: StateFlow<CleanupPhase> = _cleanupPhase.asStateFlow()

    /** Verified-present items pending the system delete dialog (MediaStore URIs). */
    private val _deletableMediaIds = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val deletableMediaIds: StateFlow<List<android.net.Uri>> = _deletableMediaIds.asStateFlow()

    /** Not-people items selected for deletion, awaiting the system delete dialog (MediaStore URIs). */
    private val _notPeopleDeleteIds = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val notPeopleDeleteIds: StateFlow<List<android.net.Uri>> = _notPeopleDeleteIds.asStateFlow()

    /**
     * Every status row, unfiltered, recomputed from the cache + queue. The filtered
     * view and the library-wide totals both derive from this so the header counts
     * stay independent of the active filter.
     */
    private val allRows: StateFlow<List<StatusRow>> =
        combine(
            locator.syncedCacheDao().observeAll(),
            locator.pendingUploadDao().observeAll(),
            locator.failureDao().observeAll(),
        ) { synced, pending, failures ->
            buildAllRows(synced, pending, failures)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Self-clearing "Sync Now" in-flight set: as the rows flip, an item leaves
    // [syncingNow] once the engine has reported its outcome (see [nextSyncing]).
    // Declared here (after [allRows] + [_syncingNow]) so the collector never runs
    // against an uninitialised property — viewModelScope dispatches eagerly on the
    // main thread, so an init block above these fields would deref them as null.
    init {
        viewModelScope.launch {
            allRows.collect { rows ->
                val (next, activated) = nextSyncing(_syncingNow.value, syncingActivated, rows)
                syncingActivated = activated
                _syncingNow.value = next
            }
        }
    }

    /** The status rows shown on the main view, after applying the active filter. */
    val rows: StateFlow<List<StatusRow>> =
        combine(allRows, _filter) { all, filter ->
            applyFilter(all, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Library-wide counts for the header, filter-independent. */
    val totals: StateFlow<StatusTotals> =
        allRows
            .map { StatusTotals.fromRows(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusTotals(0, 0, 0))

    /**
     * Per-file failures for the failures modal — the authoritative record of which
     * files didn't back up and why. Newest first (the DAO orders by failure time).
     */
    val failures: StateFlow<List<FailureDetail>> =
        locator.failureDao().observeAll()
            .map { list -> list.map { it.toFailureDetail() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun initialScreen(): AppScreen = when {
        routingPrefs.getServerAddress().isNullOrEmpty() -> AppScreen.SERVER_SETUP
        !routingPrefs.isTrusted() -> AppScreen.PAIRING
        routingPrefs.getProfileId().isNullOrEmpty() -> AppScreen.PROFILE_PICKER
        else -> AppScreen.MAIN
    }

    // --- Server setup ---

    /**
     * Validates the entered [host]/[port] and, on a reachable server, persists
     * the address (so the api client re-points) and advances to pairing. Invalid
     * input is rejected without probing; an unreachable / bad-response server
     * surfaces an error and the screen stays on server setup.
     */
    fun connect(address: String) {
        if (_connecting.value) return
        when (val parsed = ServerProbe.parse(address)) {
            is ParseResult.Invalid -> _serverSetupError.value = parsed.reason
            is ParseResult.Valid -> probeAndConnect(address.trim(), parsed.baseUrl)
        }
    }

    private fun probeAndConnect(address: String, baseUrl: String) {
        _serverSetupError.value = null
        _connecting.value = true
        viewModelScope.launch {
            try {
                when (val result = ServerProbe.validate(apiFactory(baseUrl))) {
                    is ProbeResult.Success -> {
                        routingPrefs.setServerAddress(address)
                        _serverSetupError.value = null
                        _screen.value = AppScreen.PAIRING
                    }
                    is ProbeResult.BadResponse ->
                        _serverSetupError.value = "Server responded with ${result.code}"
                    ProbeResult.Unreachable ->
                        _serverSetupError.value = "Could not reach the server"
                }
            } finally {
                _connecting.value = false
            }
        }
    }

    // --- Pairing ---

    private var pairingJob: Job? = null

    /**
     * Resolve and display this device's pairing state, then poll for approval.
     * Re-entrant calls (e.g. re-navigating to the pairing screen) are ignored
     * while a pairing pass is already running, so polling never doubles up.
     *
     * Uses [PairingManager.beginPairing] so an already-trusted device recovers
     * its trust instead of re-registering and resetting itself.
     */
    fun startPairing() {
        if (pairingJob?.isActive == true) return
        pairingJob = viewModelScope.launch {
            when (val initial = locator.pairingManager.beginPairing()) {
                is PairingState.Trusted -> onPaired()
                is PairingState.Revoked -> _pairingState.value = PairingState.Revoked
                is PairingState.Pending -> {
                    _pairingState.value = initial
                    val code = initial.pairingCode
                    val resolved = locator.pairingManager.pollUntilResolved { state ->
                        // Preserve the displayed code while pending.
                        _pairingState.value = when (state) {
                            is PairingState.Pending -> PairingState.Pending(code)
                            else -> state
                        }
                    }
                    if (resolved is PairingState.Trusted) onPaired()
                }
            }
        }
    }

    private fun onPaired() {
        _pairingState.value = PairingState.Trusted
        loadProfiles()
        _screen.value = AppScreen.PROFILE_PICKER
    }

    // --- Profiles ---

    fun loadProfiles() {
        viewModelScope.launch {
            // A failed read (server briefly unreachable) leaves the current list as-is
            // rather than crashing the picker.
            runCatching { apiProvider().profiles() }.getOrNull()?.let { _profiles.value = it }
        }
    }

    fun chooseProfile(profile: ProfileDto) {
        routingPrefs.setProfileId(profile.profileId)
        _profileNotice.value = null
        _screen.value = AppScreen.MAIN
    }

    /**
     * Create a profile from the picker's inline field. On success the new profile is
     * auto-selected and the app advances to the main screen (no second tap). On a
     * 409/400 the picker stays put and surfaces a distinct, correctable message.
     */
    fun createProfile(rawName: String) {
        viewModelScope.launch {
            _createProfileError.value = null
            try {
                val created = apiProvider().createProfile(
                    imagesorter.sync.data.api.dto.ProfileRequest(rawName.trim()),
                )
                chooseProfile(created)
            } catch (e: retrofit2.HttpException) {
                _createProfileError.value = profileErrorMessage(e.code())
            }
        }
    }

    /** Distinct human-readable message per server rejection code. `internal` so the
     * mapping is unit-testable without standing up the network seam. */
    internal fun profileErrorMessage(code: Int): String = when (code) {
        HTTP_CONFLICT -> "A profile with that name already exists — pick a different one."
        HTTP_BAD_REQUEST -> "That name isn't valid. Use letters, digits and spaces (max 64)."
        else -> "Couldn't create the profile (error $code). Please try again."
    }

    /** Route back to the picker with a notice after a run found the profile removed. */
    internal fun onSyncProfileRemoved() {
        _profileNotice.value = imagesorter.sync.sync.SyncEngine.PROFILE_REMOVED_MESSAGE
        _screen.value = AppScreen.PROFILE_PICKER
        loadProfiles()
    }

    // --- Sync ---

    fun syncNow() {
        syncTrigger.requestSync()
    }

    /**
     * App-open auto-sync. When the gate passes (the active network satisfies the
     * user's sync-network setting AND no successful full run within the throttle
     * window) start a FULL sync via the same trigger the manual "Back up now" uses.
     * Network detection lives in the Activity (this VM holds no Android types), which
     * passes [isNetworkAllowed]. Wall-clock now is used so the throttle compares
     * against the persisted last-full-sync timestamp correctly.
     */
    fun maybeAutoSyncOnOpen(isNetworkAllowed: Boolean) {
        val eligible = imagesorter.sync.sync.shouldAutoSyncOnOpen(
            isNetworkAllowed = isNetworkAllowed,
            lastFullSyncAtMillis = lastFullSyncAtMillis(),
            nowMillis = System.currentTimeMillis(),
        )
        if (eligible) syncTrigger.requestSync(silent = true)
    }

    /**
     * Refresh the working set on app open: enumerate the device + reconcile so the
     * main view shows what still needs backing up, without starting an upload.
     * Runs off the main thread (MediaStore enumeration blocks) and is a no-op while
     * a sync is already running.
     */
    fun discoverNow() {
        _discovering.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try { locator.syncEngine.discover() } finally { _discovering.value = false }
        }
    }

    /** Permission flow result: refresh discovery once media access is granted. */
    fun onMediaPermissionResult() {
        if (_screen.value == AppScreen.MAIN) discoverNow()
    }

    fun setFilter(filter: StatusFilter) {
        _filter.value = filter
    }

    // --- Cleanup ---

    fun openCleanup() {
        _cleanupPhase.value = CleanupPhase.IDLE
        _screen.value = AppScreen.CLEANUP
    }

    fun closeCleanup() {
        _screen.value = AppScreen.MAIN
    }

    fun startCleanup() {
        viewModelScope.launch {
            _cleanupPhase.value = CleanupPhase.VERIFYING
            val present: List<Identity> = locator.cleanupManager.verifySyncedItems()
            // Map the server-confirmed identities back to the local MediaStore ids the
            // system delete dialog needs; only files whose local id is known are offered.
            _deletableMediaIds.value = resolveLocalIds(present)
            _cleanupPhase.value = CleanupPhase.READY_TO_REMOVE
        }
    }

    /**
     * Map each server-confirmed identity back to its local MediaStore uri via the
     * synced cache, which stores the `MediaStore._ID` (+ mime) for every synced row —
     * the same rows the "safe on the server" tally counts. Only rows whose local id is
     * known are offered; the reconcile pass keeps the cache pruned of locally-deleted
     * media, and the system delete dialog is the final gate. `internal` so the mapping
     * is unit-testable against a seeded cache.
     */
    internal suspend fun resolveLocalIds(present: List<Identity>): List<android.net.Uri> {
        if (present.isEmpty()) return emptyList()
        val presentKeys = present.mapTo(HashSet()) { Triple(it.name, it.createdOn, it.size) }
        return locator.syncedCacheDao().syncedItems()
            .filter { Triple(it.name, it.createdOn, it.size) in presentKeys }
            .mapNotNull { entity ->
                entity.mediaStoreId?.let { id -> mediaContentUri(id, entity.mimeType ?: "image/jpeg") }
            }
    }

    /** Build the system delete request for the verified-present MediaStore URIs. */
    fun buildDeleteRequest(uris: List<android.net.Uri>): android.content.IntentSender? =
        locator.cleanupManager.buildDeleteRequest(uris)

    fun onDeleteCompleted() {
        _deletableMediaIds.value = emptyList()
        _cleanupPhase.value = CleanupPhase.IDLE
        _screen.value = AppScreen.MAIN
    }

    // --- Not People review actions ---

    /**
     * "Sync anyway" override for selected not-people items: rebuild [MediaItem]s from
     * the local cache (+ a MediaStore content uri) and force-upload them. The engine
     * opens a force_place session so the server skips classification and places them
     * in the primary group; each row flips to SYNCED on success and leaves the set.
     */
    fun overrideSelected(rows: List<StatusRow>) {
        val ids = rows.mapNotNull { it.mediaStoreId }.toSet()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val items = locator.syncedCacheDao().unclassifiedItems()
                .filter { it.mediaStoreId in ids }
                .mapNotNull { entity ->
                    val id = entity.mediaStoreId ?: return@mapNotNull null
                    val mime = entity.mimeType ?: "image/jpeg"
                    MediaItem(
                        mediaStoreId = id,
                        uri = mediaContentUri(id, mime),
                        identity = Identity(entity.name, entity.createdOn, entity.size),
                        mimeType = mime,
                    )
                }
            locator.syncEngine.overrideUpload(items)
        }
    }

    /**
     * "Sync Now" for a single working-set item: resolve its [MediaItem] by MediaStore
     * id from the pending/synced caches and hand it to the engine's force_place
     * override path, so it is uploaded and placed into the profile's primary group
     * immediately — not left waiting for a manual "Back up now". The id joins
     * [syncingNow] synchronously (before the async work) so the button reflects the
     * tap at once; it leaves once the engine reports the outcome (see [nextSyncing]).
     */
    fun syncItemNow(row: StatusRow) {
        val id = row.mediaStoreId ?: return
        _syncingNow.value = _syncingNow.value + id
        viewModelScope.launch(Dispatchers.Default) {
            val item = resolveMediaItem(id) ?: return@launch
            locator.syncEngine.overrideUpload(listOf(item))
        }
    }

    /**
     * Rebuild the [MediaItem] for a working-set row by MediaStore id. A PENDING /
     * IN_PROGRESS row lives in the pending queue (full identity there); a FAILED row
     * lives in the synced cache. `internal` so the resolution is unit-testable.
     */
    internal suspend fun resolveMediaItem(mediaStoreId: Long): MediaItem? {
        locator.pendingUploadDao().pending()
            .firstOrNull { it.mediaStoreId == mediaStoreId }
            ?.let { p ->
                return MediaItem(
                    mediaStoreId = p.mediaStoreId,
                    uri = mediaContentUri(p.mediaStoreId, p.mimeType),
                    identity = Identity(p.name, p.createdOn, p.size),
                    mimeType = p.mimeType,
                )
            }
        return locator.syncedCacheDao().observeAll().first()
            .firstOrNull { it.mediaStoreId == mediaStoreId }
            ?.let { s ->
                val id = s.mediaStoreId ?: return null
                val mime = s.mimeType ?: "image/jpeg"
                MediaItem(
                    mediaStoreId = id,
                    uri = mediaContentUri(id, mime),
                    identity = Identity(s.name, s.createdOn, s.size),
                    mimeType = mime,
                )
            }
    }

    /**
     * Recompute the "Sync Now" in-flight set from the latest rows. [activated] tracks
     * ids seen in an active (PENDING/IN_PROGRESS) row since their tap so a fresh FAILED
     * outcome (drop → re-enable the button) is told apart from the FAILED state a retry
     * started from (keep → still syncing). A SYNCED / gone / not-people row drops the id.
     * `internal` and pure so the self-clearing behaviour is unit-testable.
     */
    internal fun nextSyncing(
        inFlight: Set<Long>,
        activated: Set<Long>,
        rows: List<StatusRow>,
    ): Pair<Set<Long>, Set<Long>> {
        val statusById = rows.mapNotNull { r -> r.mediaStoreId?.let { it to r.status } }.toMap()
        val nextActivated = activated.toHashSet()
        val nextInFlight = inFlight.filterTo(HashSet()) { id ->
            when (statusById[id]) {
                SyncStatus.PENDING, SyncStatus.IN_PROGRESS -> {
                    nextActivated.add(id)
                    true
                }
                SyncStatus.FAILED -> id !in nextActivated
                else -> false
            }
        }
        nextActivated.retainAll(nextInFlight)
        return nextInFlight to nextActivated
    }

    /** Queue selected not-people items for the system delete dialog (Activity-owned). */
    fun requestNotPeopleDelete(rows: List<StatusRow>) {
        _notPeopleDeleteIds.value = rows.mapNotNull { row ->
            val id = row.mediaStoreId ?: return@mapNotNull null
            mediaContentUri(id, row.mimeType ?: "image/jpeg")
        }
    }

    /**
     * After the system delete dialog returns. Always clears the queued ids so the
     * launch effect doesn't re-fire, but only prunes the cache rows when the user
     * actually confirmed ([deleted] == true) — cancelling must leave the tile.
     *
     * When confirmed, the rows are pruned from both caches by MediaStore id,
     * whatever the status was — not just the UNCLASSIFIED "not people" rows — so
     * the tile leaves the grid immediately (the file is gone; the row is stale
     * until the next scan).
     */
    fun onNotPeopleDeleteFinished(deleted: Boolean) {
        val uris = _notPeopleDeleteIds.value
        _notPeopleDeleteIds.value = emptyList()
        if (!deleted || uris.isEmpty()) return
        val ids = uris.map { android.content.ContentUris.parseId(it) }
        viewModelScope.launch(Dispatchers.Default) {
            locator.syncedCacheDao().deleteByMediaStoreIds(ids)
            locator.pendingUploadDao().deleteByMediaStoreIds(ids)
        }
    }

    private fun mediaContentUri(id: Long, mimeType: String): android.net.Uri {
        val collection = if (mimeType.startsWith("video/")) {
            android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        } else {
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        }
        return android.content.ContentUris.withAppendedId(collection, id)
    }

    // --- Row projection ---

    private fun buildAllRows(
        synced: List<SyncedCacheEntity>,
        pending: List<PendingUploadEntity>,
        failures: List<FailureEntity>,
    ): List<StatusRow> {
        val failureByName = failures.associateBy { it.name }
        val syncedRows = synced.map { row ->
            StatusRow(
                name = row.name,
                status = runCatching { SyncStatus.valueOf(row.status) }.getOrDefault(SyncStatus.SYNCED),
                failureReason = failureByName[row.name]?.reason,
                // Identity (name+createdOn+size) is the synced_cache primary key.
                key = "s:${row.name}:${row.createdOn}:${row.size}",
                mediaStoreId = row.mediaStoreId,
                mimeType = row.mimeType,
            )
        }
        val pendingRows = pending
            .filter { it.status != SyncStatus.SYNCED.name }
            .map { row ->
                StatusRow(
                    name = row.name,
                    status = runCatching { SyncStatus.valueOf(row.status) }.getOrDefault(SyncStatus.PENDING),
                    // mediaStoreId is unique per phone media file.
                    key = "p:${row.mediaStoreId}",
                    mediaStoreId = row.mediaStoreId,
                    mimeType = row.mimeType,
                )
            }

        // distinctBy is a safety net: the grid key must be unique, so collapse any
        // duplicate rows (e.g. stale pending rows left by an earlier double-run)
        // rather than letting the LazyGrid crash on a repeated key.
        return (pendingRows + syncedRows).distinctBy { it.key }
    }

    /** The stored reason is a [FailureReason] name; map it back, falling to UNKNOWN. */
    private fun FailureEntity.toFailureDetail(): FailureDetail =
        FailureDetail(
            name = name,
            reason = runCatching { FailureReason.valueOf(reason) }.getOrDefault(FailureReason.UNKNOWN),
            retryable = retryable,
        )

    /**
     * Maps the full row set to the slice the active filter shows. `internal` (not
     * private) so it can be unit-tested as the pure projection it is, without
     * standing up the DAO/DB seam the public `rows` flow needs.
     *
     * WORKING_SET excludes UNCLASSIFIED ("Not people" was a deliberate not-sync
     * decision); NOT_PEOPLE shows only those.
     */
    internal fun applyFilter(all: List<StatusRow>, filter: StatusFilter): List<StatusRow> =
        when (filter) {
            StatusFilter.ALL -> all
            StatusFilter.SYNCED_TODAY -> all.filter { it.status == SyncStatus.SYNCED }
            StatusFilter.NOT_PEOPLE -> all.filter { it.status == SyncStatus.UNCLASSIFIED }
            StatusFilter.WORKING_SET ->
                all.filter {
                    it.status != SyncStatus.UNCLASSIFIED &&
                        (it.status != SyncStatus.SYNCED || it.failureReason != null)
                }
        }

    companion object {
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_CONFLICT = 409
    }
}

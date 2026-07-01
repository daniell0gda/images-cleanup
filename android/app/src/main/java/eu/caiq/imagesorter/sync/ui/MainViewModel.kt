package eu.caiq.imagesorter.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.caiq.imagesorter.sync.ServiceLocator
import eu.caiq.imagesorter.sync.data.api.ParseResult
import eu.caiq.imagesorter.sync.data.api.ProbeResult
import eu.caiq.imagesorter.sync.data.api.ServerProbe
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.data.db.entity.FailureEntity
import eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import eu.caiq.imagesorter.sync.domain.model.FailureReason
import eu.caiq.imagesorter.sync.domain.model.Identity
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.pairing.PairingState
import eu.caiq.imagesorter.sync.ui.screens.CleanupPhase
import eu.caiq.imagesorter.sync.ui.screens.FailureDetail
import eu.caiq.imagesorter.sync.ui.screens.StatusFilter
import eu.caiq.imagesorter.sync.ui.screens.StatusRow
import eu.caiq.imagesorter.sync.ui.screens.StatusTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
enum class HomeTab { PHOTOS, ALBUMS, SYNC }

/**
 * Narrow read/write seam over the prefs the routing + connect flow touches. Kept
 * separate from [ServiceLocator] so the routing logic is unit-testable with an
 * in-memory fake (the real [eu.caiq.imagesorter.sync.data.prefs.SecurePrefs]
 * uses EncryptedSharedPreferences, which does not run under the JVM test harness).
 */
interface RoutingPrefs {
    fun getServerAddress(): String?
    fun setServerAddress(value: String?)
    fun isTrusted(): Boolean
    fun getProfileId(): String?
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
    private val apiFactory: (String) -> eu.caiq.imagesorter.sync.data.api.SyncApi = locator::buildApi,
) : ViewModel() {

    private val _screen = MutableStateFlow(initialScreen())
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _homeTab = MutableStateFlow(HomeTab.PHOTOS)

    /** Selected bottom-nav destination in the post-pairing home shell; opens on Photos. */
    val homeTab: StateFlow<HomeTab> = _homeTab.asStateFlow()

    /** Switch the home shell's bottom-nav tab (Photos | Sync). */
    fun selectHomeTab(tab: HomeTab) {
        _homeTab.value = tab
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

    private val _filter = MutableStateFlow(StatusFilter.WORKING_SET)
    val filter: StateFlow<StatusFilter> = _filter.asStateFlow()

    /**
     * Live sync progress. The engine is a process-wide singleton, so this is the
     * same flow the foreground service drives — the UI mirrors the running pass.
     */
    val syncProgress: StateFlow<eu.caiq.imagesorter.sync.sync.SyncProgress> =
        locator.syncEngine.progress

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
        viewModelScope.launch { _profiles.value = locator.api.profiles() }
    }

    fun chooseProfile(profile: ProfileDto) {
        locator.securePrefs.setProfileId(profile.profileId)
        _screen.value = AppScreen.MAIN
    }

    // --- Sync ---

    fun syncNow() {
        locator.syncTrigger.requestSync()
    }

    /**
     * Refresh the working set on app open: enumerate the device + reconcile so the
     * main view shows what still needs backing up, without starting an upload.
     * Runs off the main thread (MediaStore enumeration blocks) and is a no-op while
     * a sync is already running.
     */
    fun discoverNow() {
        viewModelScope.launch(Dispatchers.Default) { locator.syncEngine.discover() }
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
            // Resolve verified identities back to local MediaStore ids via the
            // pending/synced cache is out of scope for the skeleton; the Activity
            // re-queries MediaStore by identity before launching the delete.
            _deletableMediaIds.value = resolveLocalIds(present)
            _cleanupPhase.value = CleanupPhase.READY_TO_REMOVE
        }
    }

    /**
     * TODO(designer/impl): map verified identities to current MediaStore ids.
     * Skeleton returns an empty list; the real implementation re-queries
     * MediaStore by (name, created_on, size) so only files still on the phone are
     * offered for deletion.
     */
    private fun resolveLocalIds(present: List<Identity>): List<android.net.Uri> = emptyList()

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

    /** Queue selected not-people items for the system delete dialog (Activity-owned). */
    fun requestNotPeopleDelete(rows: List<StatusRow>) {
        _notPeopleDeleteIds.value = rows.mapNotNull { row ->
            val id = row.mediaStoreId ?: return@mapNotNull null
            mediaContentUri(id, row.mimeType ?: "image/jpeg")
        }
    }

    /**
     * After the system delete dialog returns: prune the cache rows for the deleted
     * ids so they leave the grid (reconcile never deletes UNCLASSIFIED rows itself).
     */
    fun onNotPeopleDeleteCompleted() {
        val uris = _notPeopleDeleteIds.value
        _notPeopleDeleteIds.value = emptyList()
        if (uris.isEmpty()) return
        val ids = uris.map { android.content.ContentUris.parseId(it) }.toSet()
        viewModelScope.launch(Dispatchers.Default) {
            locator.syncedCacheDao().unclassifiedItems()
                .filter { it.mediaStoreId in ids }
                .forEach { locator.syncedCacheDao().delete(it.name, it.createdOn, it.size) }
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
}

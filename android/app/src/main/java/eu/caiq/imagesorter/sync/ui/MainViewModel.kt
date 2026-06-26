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
import eu.caiq.imagesorter.sync.domain.model.Identity
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.pairing.PairingState
import eu.caiq.imagesorter.sync.ui.screens.CleanupPhase
import eu.caiq.imagesorter.sync.ui.screens.StatusFilter
import eu.caiq.imagesorter.sync.ui.screens.StatusRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Top-level screen the Activity should render. */
enum class AppScreen { SERVER_SETUP, PAIRING, PROFILE_PICKER, MAIN, CLEANUP }

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

    private val _cleanupPhase = MutableStateFlow(CleanupPhase.IDLE)
    val cleanupPhase: StateFlow<CleanupPhase> = _cleanupPhase.asStateFlow()

    /** Verified-present items pending the system delete dialog (local ids). */
    private val _deletableMediaIds = MutableStateFlow<List<Long>>(emptyList())
    val deletableMediaIds: StateFlow<List<Long>> = _deletableMediaIds.asStateFlow()

    /** The status rows shown on the main view, recomputed from the cache + queue. */
    val rows: StateFlow<List<StatusRow>> =
        combine(
            locator.syncedCacheDao().observeAll(),
            locator.pendingUploadDao().observeAll(),
            locator.failureDao().observeAll(),
            _filter,
        ) { synced, pending, failures, filter ->
            buildRows(synced, pending, failures, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun connect(host: String, port: String) {
        if (_connecting.value) return
        when (val parsed = ServerProbe.parse(host, port)) {
            is ParseResult.Invalid -> _serverSetupError.value = parsed.reason
            is ParseResult.Valid -> probeAndConnect(host.trim(), port.trim().toInt(), parsed.baseUrl)
        }
    }

    private fun probeAndConnect(host: String, port: Int, baseUrl: String) {
        _serverSetupError.value = null
        _connecting.value = true
        viewModelScope.launch {
            try {
                when (val result = ServerProbe.validate(apiFactory(baseUrl))) {
                    is ProbeResult.Success -> {
                        routingPrefs.setServerAddress("$host:$port")
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

    fun startPairing() {
        viewModelScope.launch {
            val code = locator.pairingManager.register()
            _pairingState.value = PairingState.Pending(code)
            val resolved = locator.pairingManager.pollUntilResolved { state ->
                // Preserve the displayed code while pending.
                _pairingState.value = when (state) {
                    is PairingState.Pending -> PairingState.Pending(code)
                    else -> state
                }
            }
            if (resolved is PairingState.Trusted) {
                loadProfiles()
                _screen.value = AppScreen.PROFILE_PICKER
            }
        }
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
    private fun resolveLocalIds(present: List<Identity>): List<Long> = emptyList()

    /** Build the system delete request for the verified-present local ids. */
    fun buildDeleteRequest(mediaIds: List<Long>): android.content.IntentSender? =
        locator.cleanupManager.buildDeleteRequest(mediaIds)

    fun onDeleteCompleted() {
        _deletableMediaIds.value = emptyList()
        _cleanupPhase.value = CleanupPhase.IDLE
        _screen.value = AppScreen.MAIN
    }

    // --- Row projection ---

    private fun buildRows(
        synced: List<SyncedCacheEntity>,
        pending: List<PendingUploadEntity>,
        failures: List<FailureEntity>,
        filter: StatusFilter,
    ): List<StatusRow> {
        val failureByName = failures.associateBy { it.name }
        val syncedRows = synced.map { row ->
            StatusRow(
                name = row.name,
                status = runCatching { SyncStatus.valueOf(row.status) }.getOrDefault(SyncStatus.SYNCED),
                failureReason = failureByName[row.name]?.reason,
            )
        }
        val pendingRows = pending
            .filter { it.status != SyncStatus.SYNCED.name }
            .map { row ->
                StatusRow(
                    name = row.name,
                    status = runCatching { SyncStatus.valueOf(row.status) }.getOrDefault(SyncStatus.PENDING),
                )
            }

        val all = pendingRows + syncedRows
        return when (filter) {
            StatusFilter.ALL -> all
            StatusFilter.SYNCED_TODAY -> all.filter { it.status == SyncStatus.SYNCED }
            StatusFilter.WORKING_SET ->
                all.filter { it.status != SyncStatus.SYNCED || it.failureReason != null }
        }
    }
}

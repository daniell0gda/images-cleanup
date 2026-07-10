package eu.caiq.imagesorter.sync.sync

import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.dto.IdentityDto
import eu.caiq.imagesorter.sync.data.api.dto.OpenSessionRequest
import eu.caiq.imagesorter.sync.data.api.dto.OutcomeDto
import eu.caiq.imagesorter.sync.data.db.dao.FailureDao
import eu.caiq.imagesorter.sync.data.db.dao.PendingUploadDao
import eu.caiq.imagesorter.sync.data.db.dao.SyncedCacheDao
import eu.caiq.imagesorter.sync.data.db.entity.FailureEntity
import eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import eu.caiq.imagesorter.sync.data.media.MediaSource
import eu.caiq.imagesorter.sync.data.prefs.SecurePrefs
import eu.caiq.imagesorter.sync.data.prefs.SyncPrefs
import eu.caiq.imagesorter.sync.domain.model.FailureReason
import eu.caiq.imagesorter.sync.domain.model.Identity
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException
import java.util.UUID

/**
 * Orchestrates a single sync run: **discover → reconcile → upload → report**.
 *
 *  - **Discover** enumerates MediaStore (incremental via the generation
 *    watermark; full on first run).
 *  - **Reconcile is always full**: every discovered identity is batched to
 *    `/reconcile`; only `already_synced == false` items are uploaded.
 *  - **Upload** opens one session for the chosen profile and uploads files with
 *    bounded concurrency (configurable, default ~6), newest-first, resuming each
 *    file via [TusUploader]. The pending queue is persisted so a restart resumes.
 *  - **Report** completes the session, polls `/outcomes`, and writes synced /
 *    failed state into the Room cache. Retryable failures are re-queued (left in
 *    pending); terminal failures are recorded and dropped from pending.
 *
 * Trust and network gating are decided by the caller (service); a 401 anywhere
 * clears the token via [SecurePrefs] and ends the run so the app re-pairs.
 */
class SyncEngine(
    private val api: SyncApi,
    private val scanner: MediaSource,
    private val uploader: TusUploader,
    private val securePrefs: SyncPrefs,
    private val syncedCacheDao: SyncedCacheDao,
    private val pendingUploadDao: PendingUploadDao,
    private val failureDao: FailureDao,
    private val uploadBatchSize: Int = UPLOAD_BATCH,
    // Monotonic clock for the discover() debounce — NOT the wall clock. A wall-clock
    // backward jump (NTP correction / user changing device time) would make the
    // elapsed delta negative and wrongly suppress a discover; elapsedRealtime can't
    // run backwards. Injectable so tests can drive it with a FakeClock.
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val _syncCompletions = MutableStateFlow(0)

    /**
     * A monotonically-increasing counter bumped each time a batch's session is completed and its
     * outcomes reported (server-side grouping done). Observers (the Photos timeline) refresh on
     * each change so newly synced items appear without user interaction. The initial value is not
     * a completion.
     */
    val syncCompletions: StateFlow<Int> = _syncCompletions.asStateFlow()

    // Single atomic gate shared by run() and discover(). Acquired with
    // compareAndSet at entry and released in finally; closes the check-then-act
    // gap so a re-entrant run()/discover() cannot overlap an active pass.
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)

    // Watermark of the last completed full enumerate+reconcile, used to debounce
    // back-to-back discover() calls. run() ignores this and always reconciles.
    @Volatile private var lastFullReconcileAt: Long? = null

    // "Sync anyway" overrides requested while a pass was active. They are already
    // unmarked + queued the instant the user taps; this remembers they still need a
    // force_place upload so the request is deferred (never dropped) and drained once
    // the guard is free.
    private val overrideQueue = java.util.concurrent.ConcurrentLinkedQueue<MediaItem>()

    /**
     * Run one full sync. Safe to call again to resume — discovery + the persisted
     * pending queue make a re-run idempotent.
     */
    suspend fun run() {
        // Atomic re-entrancy guard: a second run() (or a discover()) while a pass is
        // already active is a no-op — it opens no session and starts no upload pass.
        if (!active.compareAndSet(false, true)) return
        try {
            val profileId = securePrefs.getProfileId()
            if (profileId.isNullOrEmpty()) {
                _progress.value = SyncProgress(phase = SyncPhase.ERROR, message = "No profile selected")
                return
            }

            try {
                val toUpload = discoverAndReconcile()
                if (toUpload.isEmpty()) {
                    _progress.value = SyncProgress(phase = SyncPhase.DONE, message = "Nothing to sync")
                    advanceWatermark()
                    return
                }

                uploadPending(profileId, toUpload)
                advanceWatermark()

                _progress.value = _progress.value.copy(phase = SyncPhase.DONE)
            } catch (e: HttpException) {
                _progress.value = when (e.code()) {
                    HTTP_UNAUTHORIZED -> {
                        // Revoked or invalid token → force re-pair on next launch.
                        securePrefs.clearTokenForRepair()
                        SyncProgress(phase = SyncPhase.ERROR, message = "Re-pairing required")
                    }
                    // Session-open 503: the server has no sync template configured.
                    // A distinct, non-generic state so the UI can explain it.
                    HTTP_UNAVAILABLE ->
                        SyncProgress(phase = SyncPhase.ERROR, message = SYNC_NOT_CONFIGURED_MESSAGE)
                    // Session-open 404: the chosen profile was removed server-side.
                    // Self-heal by forgetting it so the app re-routes to the picker.
                    HTTP_NOT_FOUND -> {
                        securePrefs.clearProfileId()
                        SyncProgress(phase = SyncPhase.ERROR, message = PROFILE_REMOVED_MESSAGE)
                    }
                    else -> SyncProgress(phase = SyncPhase.ERROR, message = "Server error ${e.code()}")
                }
            }
        } finally {
            active.set(false)
            drainOverrides()
        }
    }

    /**
     * Discovery + reconcile only — refresh the working set (what still needs
     * backing up) without uploading. Called on app open so the main view reflects
     * reality before the user taps "Back up now". A no-op while a run is already
     * active so it never races the foreground upload pass. A 401 forces re-pair;
     * an unreachable server leaves the existing cache untouched.
     */
    suspend fun discover() {
        // Same atomic guard as run(): bail if a pass is already active. This closes
        // the check-then-act gap a progress-phase read left open (run() can hold the
        // guard before it has set any busy phase), so discover never races a run.
        if (!active.compareAndSet(false, true)) return
        try {
            // Debounce: a discover() inside the dedup window of the last full pass
            // reuses that just-computed working set instead of re-enumerating and
            // re-reconciling the whole library.
            val last = lastFullReconcileAt
            val elapsed = last?.let { now() - it }
            // A monotonic source can't go backwards, but guard the delta anyway:
            // treat a negative elapsed (a wall-clock backward jump if the source is
            // ever non-monotonic) as window-expired so a discover is never wrongly
            // suppressed.
            if (elapsed != null && elapsed in 0 until DISCOVER_DEDUP_MILLIS) {
                _progress.value = SyncProgress(phase = SyncPhase.IDLE)
                return
            }
            discoverAndReconcile()
            _progress.value = SyncProgress(phase = SyncPhase.IDLE)
        } catch (e: HttpException) {
            if (e.code() == HTTP_UNAUTHORIZED) {
                securePrefs.clearTokenForRepair()
                _progress.value = SyncProgress(phase = SyncPhase.ERROR, message = "Re-pairing required")
            } else {
                _progress.value = SyncProgress(phase = SyncPhase.ERROR, message = "Server error ${e.code()}")
            }
        } catch (e: java.io.IOException) {
            // Server unreachable on open: keep whatever the cache already shows.
            _progress.value = SyncProgress(phase = SyncPhase.IDLE)
        } finally {
            active.set(false)
            drainOverrides()
        }
    }

    // --- Discover + reconcile -------------------------------------------------

    /** Enumerate, reconcile in batches, persist the pending queue, return items. */
    private suspend fun discoverAndReconcile(): List<MediaItem> {
        _progress.value = SyncProgress(phase = SyncPhase.DISCOVERING)
        // Discovery may be incremental, but the design mandates a FULL reconcile
        // every run, so enumerate the whole library here for the reconcile pass.
        // Restricted to the camera folder so app media (Viber, screenshots, etc.)
        // is never backed up — only photos/videos the camera produced.
        val items = scanner.enumerate(sinceGeneration = SecurePrefs.NO_WATERMARK, folders = CAMERA_FOLDERS)
        val byIdentity = items.associateBy { it.identity }

        _progress.value = SyncProgress(phase = SyncPhase.RECONCILING, totalFiles = items.size)

        // Identities the user reviewed / the server discarded as "not people".
        // The server reports these already_synced=false forever, so skip them: they
        // must never re-enter the upload set or the pending queue, and their cache
        // row is left untouched so the Not People set stays visible.
        val unclassified = syncedCacheDao.unclassifiedItems()
            .map { Identity(it.name, it.createdOn, it.size) }
            .toHashSet()

        val notSynced = ArrayList<MediaItem>()
        val resumeByMediaId = HashMap<Long, ResumePoint>()
        items.chunked(RECONCILE_BATCH).forEach { batch ->
            val response = api.reconcile(batch.map { it.identity.toDto() })
            response.results.forEach { result ->
                val identity = Identity(result.name, result.createdOn, result.size)
                val item = byIdentity[identity] ?: return@forEach
                if (identity in unclassified) {
                    return@forEach
                }
                if (result.alreadySynced) {
                    syncedCacheDao.upsert(
                        item.identity.toSyncedCache(
                            SyncStatus.SYNCED,
                            mediaStoreId = item.mediaStoreId,
                            mimeType = item.mimeType,
                        ),
                    )
                    // A `complete` timeout can leave this file as a pending/IN_PROGRESS
                    // row even though the server synced it; drop that stuck row so no
                    // ghost "in progress" tile lingers next to the synced one.
                    pendingUploadDao.deleteByIdentity(
                        item.identity.name,
                        item.identity.createdOn,
                        item.identity.size,
                    )
                } else {
                    notSynced += item
                    // The server still holds bytes from an interrupted run: resume
                    // into that session instead of re-uploading from scratch.
                    val sessionId = result.resumeSessionId
                    val fileId = result.resumeFileId
                    if (sessionId != null && fileId != null) {
                        resumeByMediaId[item.mediaStoreId] =
                            ResumePoint(sessionId, fileId, result.uploadedOffset)
                    }
                }
            }
        }

        // Persist the pending queue (newest-first ordering via sortKey). Reuse the
        // fileId of any row already pending for the same media file so a re-run
        // (e.g. the user tapping "Back up now" twice) REPLACEs that row instead of
        // inserting a duplicate — fileId is the primary key, so a fresh UUID per
        // run would queue every file twice. A resumable file adopts the server's
        // session/file id and offset so its already-uploaded bytes are kept.
        val existingByMediaId = pendingUploadDao.pending().associateBy { it.mediaStoreId }
        pendingUploadDao.upsert(
            notSynced.map { item ->
                val resume = resumeByMediaId[item.mediaStoreId]
                item.toPending(
                    fileId = resume?.fileId ?: existingByMediaId[item.mediaStoreId]?.fileId,
                    sessionId = resume?.sessionId,
                    serverOffset = resume?.offset ?: 0,
                )
            },
        )
        lastFullReconcileAt = now()
        return notSynced
    }

    // --- Sessions + upload ----------------------------------------------------

    private suspend fun openSession(profileId: String, forcePlace: Boolean = false): String =
        api.openSession(OpenSessionRequest(profileId, forcePlace)).sessionId

    /**
     * Force-upload a user-selected set of "not people" items ("Sync anyway").
     *
     * The items leave the Not People set the instant this is called: their local
     * UNCLASSIFIED mark is cleared and they enter the pending queue immediately,
     * unconditionally — even if a sync pass is already active — so the request is
     * never lost and nothing lingers in the list. The force_place upload then runs
     * right away if the engine is free, or is deferred and drained by the active
     * pass when it releases the guard (see [drainOverrides]).
     */
    suspend fun overrideUpload(items: List<MediaItem>) {
        if (items.isEmpty()) return

        // Clear the not-syncable mark and queue them now so the upload plumbing
        // (uploadAll / completeAndReport) can find their file ids, and so the Not
        // People list drops them regardless of whether a pass is currently running.
        items.forEach { item ->
            syncedCacheDao.delete(item.identity.name, item.identity.createdOn, item.identity.size)
        }
        pendingUploadDao.upsert(items.map { it.toPending() })

        overrideQueue.addAll(items)
        drainOverrides()
    }

    /**
     * Force-upload any queued "Sync anyway" overrides — but only if the engine is
     * free. While a pass holds the guard this is a no-op; that pass drains the queue
     * from its own `finally`, so a request landing mid-pass is deferred, not dropped.
     */
    private suspend fun drainOverrides() {
        while (overrideQueue.isNotEmpty()) {
            if (!active.compareAndSet(false, true)) return
            try {
                val batch = ArrayList<MediaItem>()
                while (true) {
                    batch.add(overrideQueue.poll() ?: break)
                }
                if (batch.isNotEmpty()) forcePlaceUpload(batch)
            } finally {
                active.set(false)
            }
        }
    }

    /**
     * Open a dedicated session with force_place=true per batch (the server skips
     * classification and places the files into the primary group), upload them, and
     * report. A "synced" outcome flips each row to SYNCED so it leaves the Not People
     * set; a force_place session cannot yield an unclassified outcome.
     */
    private suspend fun forcePlaceUpload(items: List<MediaItem>) {
        val profileId = securePrefs.getProfileId()
        if (profileId.isNullOrEmpty()) return
        val total = items.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        for (batch in items.chunked(uploadBatchSize)) {
            val sessionId = openSession(profileId, forcePlace = true)
            uploadAll(sessionId, batch, total, completed)
            completeAndReportSafely(sessionId)
        }
    }

    /**
     * Upload everything pending. Files the server already holds bytes for (an
     * interrupted earlier run) are resumed into their existing session and that
     * session is completed; the rest go into fresh, independent batches.
     *
     * Each session is completed and placed before the next starts, so the server
     * classifies + moves files (the "photos safe" count grows) incrementally
     * instead of only after the whole library has uploaded — essential for
     * libraries of thousands of files — and each blocking `complete` call's
     * server-side work stays bounded.
     */
    private suspend fun uploadPending(profileId: String, items: List<MediaItem>) {
        val total = items.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        _progress.value = SyncProgress(phase = SyncPhase.UPLOADING, totalFiles = total)

        val sessionByMediaId = pendingUploadDao.pending()
            .mapNotNull { row -> row.sessionId?.let { row.mediaStoreId to it } }
            .toMap()
        val (resumable, fresh) = items.partition { sessionByMediaId.containsKey(it.mediaStoreId) }

        // Resume interrupted sessions first so already-uploaded bytes are kept.
        resumable.groupBy { sessionByMediaId.getValue(it.mediaStoreId) }
            .forEach { (sessionId, group) ->
                uploadAll(sessionId, group, total, completed)
                completeAndReportSafely(sessionId)
            }

        for (batch in fresh.chunked(uploadBatchSize)) {
            val sessionId = openSession(profileId)
            uploadAll(sessionId, batch, total, completed)
            completeAndReportSafely(sessionId)
        }
    }

    /** Complete + report, swallowing a timeout so one slow batch never aborts the run. */
    private suspend fun completeAndReportSafely(sessionId: String) {
        try {
            completeAndReport(sessionId)
        } catch (e: java.io.IOException) {
            // A slow `complete` (server classifying the batch) may time out while
            // the server keeps working; those files stay pending and reconcile
            // marks them synced next run. Don't abort the whole sync.
        }
    }

    /** Upload one batch with bounded concurrency, advancing the shared counter. */
    private suspend fun uploadAll(
        sessionId: String,
        items: List<MediaItem>,
        total: Int,
        completed: java.util.concurrent.atomic.AtomicInteger,
    ) = coroutineScope {
        val concurrency = securePrefs.getUploadConcurrency()
        val semaphore = Semaphore(concurrency)
        _progress.value = _progress.value.copy(phase = SyncPhase.UPLOADING, totalFiles = total)

        // Reuse persisted file ids so resume keeps the same server-side identity.
        val pendingById = pendingUploadDao.pending().associateBy { it.mediaStoreId }

        items.forEach { item ->
            launch {
                semaphore.withPermit {
                    val fileId = pendingById[item.mediaStoreId]?.fileId ?: UUID.randomUUID().toString()
                    pendingUploadDao.setSession(fileId, sessionId)
                    pendingUploadDao.setStatus(fileId, SyncStatus.IN_PROGRESS.name)
                    try {
                        uploader.upload(sessionId, fileId, item) { offset ->
                            pendingUploadDao.setOffset(fileId, offset)
                        }
                    } catch (e: HttpException) {
                        // A 401 must abort the whole run so the app re-pairs;
                        // other transport errors leave this file in the queue
                        // (resumable next run) and let the batch continue.
                        if (e.code() == HTTP_UNAUTHORIZED) throw e
                        pendingUploadDao.setStatus(fileId, SyncStatus.PENDING.name)
                    } catch (e: java.io.IOException) {
                        pendingUploadDao.setStatus(fileId, SyncStatus.PENDING.name)
                    } finally {
                        _progress.value = _progress.value.copy(completedFiles = completed.incrementAndGet())
                    }
                }
            }
        }
    }

    // --- Complete + report ----------------------------------------------------

    private suspend fun completeAndReport(sessionId: String) {
        _progress.value = _progress.value.copy(phase = SyncPhase.REPORTING)
        api.completeSession(sessionId)

        val outcomes = api.outcomes(sessionId).outcomes
        val pendingRows = pendingUploadDao.pending()
        var failed = 0
        outcomes.forEach { outcome ->
            // Resolve by file id first; if the server reassigned the id, fall back
            // to the echoed identity so a round-trip-identical outcome is never
            // silently dropped. Use the resolved row's real file id for mutations.
            val pending = resolvePending(pendingRows, outcome) ?: return@forEach
            val identity = Identity(pending.name, pending.createdOn, pending.size)
            if (outcome.status == OUTCOME_SYNCED) {
                syncedCacheDao.upsert(
                    identity.toSyncedCache(
                        SyncStatus.SYNCED,
                        mediaStoreId = pending.mediaStoreId,
                        mimeType = pending.mimeType,
                    ),
                )
                failureDao.clear(identity.name, identity.createdOn, identity.size)
                pendingUploadDao.delete(pending.fileId)
            } else if (outcome.status == OUTCOME_UNCLASSIFIED) {
                // "Not people": the server discarded the bytes. Cache it so the user
                // can review it in the Not People set; it is neither synced nor a
                // failure, so don't record a failure or bump the failed count.
                syncedCacheDao.upsert(
                    identity.toSyncedCache(
                        SyncStatus.UNCLASSIFIED,
                        mediaStoreId = pending.mediaStoreId,
                        mimeType = pending.mimeType,
                    ),
                )
                failureDao.clear(identity.name, identity.createdOn, identity.size)
                pendingUploadDao.delete(pending.fileId)
            } else {
                failed += 1
                recordFailure(pending.fileId, identity, pending, outcome.reason, outcome.retryable)
            }
        }
        _progress.value = _progress.value.copy(failedFiles = _progress.value.failedFiles + failed)
        // Session completed + outcomes reported: signal observers to refresh the timeline.
        _syncCompletions.value += 1
    }

    private suspend fun recordFailure(
        fileId: String,
        identity: Identity,
        pending: PendingUploadEntity,
        reasonWire: String?,
        retryableFromServer: Boolean?,
    ) {
        val reason = FailureReason.fromWire(reasonWire)
        // Prefer the server's explicit retryable flag; fall back to the taxonomy.
        val retryable = retryableFromServer ?: reason.retryable
        failureDao.upsert(
            FailureEntity(
                name = identity.name,
                createdOn = identity.createdOn,
                size = identity.size,
                reason = reason.name,
                retryable = retryable,
                message = reasonWire,
                failedAt = System.currentTimeMillis(),
            ),
        )
        syncedCacheDao.upsert(
            identity.toSyncedCache(
                SyncStatus.FAILED,
                mediaStoreId = pending.mediaStoreId,
                mimeType = pending.mimeType,
            ),
        )
        if (retryable) {
            // Leave it in the pending queue (reset to PENDING) for the next run.
            pendingUploadDao.setStatus(fileId, SyncStatus.PENDING.name)
        } else {
            pendingUploadDao.delete(fileId)
        }
    }

    /**
     * Find the pending row an outcome belongs to. Prefer an exact file-id match;
     * if the server echoed a different id, fall back to the round-trip identity
     * (name) so the outcome is routed to synced/failed handling, never dropped.
     */
    private fun resolvePending(
        pendingRows: List<PendingUploadEntity>,
        outcome: OutcomeDto,
    ): PendingUploadEntity? =
        pendingRows.firstOrNull { it.fileId == outcome.fileId }
            ?: pendingRows.firstOrNull { it.name == outcome.name }

    private fun advanceWatermark() {
        val generation = scanner.currentGeneration()
        if (generation > 0) securePrefs.setMediaGeneration(generation)
    }

    // --- Mapping helpers ------------------------------------------------------

    private fun Identity.toDto() = IdentityDto(name = name, createdOn = createdOn, size = size)

    private fun Identity.toSyncedCache(
        status: SyncStatus,
        mediaStoreId: Long? = null,
        mimeType: String? = null,
    ) = SyncedCacheEntity(
        name = name,
        createdOn = createdOn,
        size = size,
        status = status.name,
        mediaStoreId = mediaStoreId,
        mimeType = mimeType,
        syncedAt = if (status == SyncStatus.SYNCED) System.currentTimeMillis() else null,
    )

    private fun MediaItem.toPending(
        fileId: String? = null,
        sessionId: String? = null,
        serverOffset: Long = 0,
    ) = PendingUploadEntity(
        fileId = fileId ?: UUID.randomUUID().toString(),
        mediaStoreId = mediaStoreId,
        name = identity.name,
        createdOn = identity.createdOn,
        size = identity.size,
        mimeType = mimeType,
        sessionId = sessionId,
        serverOffset = serverOffset,
        status = SyncStatus.PENDING.name,
        // Newest-first ordering key: created_on as epoch millis (0 if unparseable).
        sortKey = createdOnEpochMillis(identity.createdOn),
    )

    /** Where the server already holds bytes for a file from an interrupted run. */
    private data class ResumePoint(val sessionId: String, val fileId: String, val offset: Long)

    /** Parse the ISO-8601 local date-time back to epoch millis for ordering. */
    private fun createdOnEpochMillis(iso: String): Long =
        try {
            java.time.LocalDateTime.parse(iso)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            0L
        }

    companion object {
        private const val RECONCILE_BATCH = 500
        // Files per upload session. Each batch is completed and placed server-side
        // before the next, so progress is incremental and each `complete` call's
        // classification work stays bounded (keeping it under the HTTP read timeout).
        private const val UPLOAD_BATCH = 100
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAVAILABLE = 503
        private const val OUTCOME_SYNCED = "synced"
        private const val OUTCOME_UNCLASSIFIED = "unclassified"

        /** Session-open 503 UI state: the server's `sync:` template is not set up. */
        const val SYNC_NOT_CONFIGURED_MESSAGE = "Sync isn't set up on the server yet."

        /** Session-open 404 UI state: the chosen profile no longer exists server-side. */
        const val PROFILE_REMOVED_MESSAGE = "Your sync profile was removed. Choose another."

        // Window during which a repeat discover() reuses the last full reconcile
        // instead of re-enumerating the whole library. run() is never debounced.
        private const val DISCOVER_DEDUP_MILLIS = 5_000L

        // Camera output folder (MediaStore RELATIVE_PATH prefix). Restricting
        // discovery to this excludes other apps' media (Viber, WhatsApp,
        // downloads, screenshots) so only camera photos/videos are backed up.
        private val CAMERA_FOLDERS = setOf("DCIM/Camera/")
    }
}

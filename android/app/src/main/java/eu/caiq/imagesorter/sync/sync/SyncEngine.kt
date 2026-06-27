package eu.caiq.imagesorter.sync.sync

import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.dto.IdentityDto
import eu.caiq.imagesorter.sync.data.api.dto.OpenSessionRequest
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
) {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /**
     * Run one full sync. Safe to call again to resume — discovery + the persisted
     * pending queue make a re-run idempotent.
     */
    suspend fun run() {
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
            if (e.code() == HTTP_UNAUTHORIZED) {
                // Revoked or invalid token → force re-pair on next launch.
                securePrefs.clearTokenForRepair()
                _progress.value = SyncProgress(phase = SyncPhase.ERROR, message = "Re-pairing required")
            } else {
                _progress.value = SyncProgress(phase = SyncPhase.ERROR, message = "Server error ${e.code()}")
            }
        }
    }

    // --- Discover + reconcile -------------------------------------------------

    /** Enumerate, reconcile in batches, persist the pending queue, return items. */
    private suspend fun discoverAndReconcile(): List<MediaItem> {
        _progress.value = SyncProgress(phase = SyncPhase.DISCOVERING)
        // Discovery may be incremental, but the design mandates a FULL reconcile
        // every run, so enumerate the whole library here for the reconcile pass.
        val items = scanner.enumerate(sinceGeneration = SecurePrefs.NO_WATERMARK)
        val byIdentity = items.associateBy { it.identity }

        _progress.value = SyncProgress(phase = SyncPhase.RECONCILING, totalFiles = items.size)

        val notSynced = ArrayList<MediaItem>()
        val resumeByMediaId = HashMap<Long, ResumePoint>()
        items.chunked(RECONCILE_BATCH).forEach { batch ->
            val response = api.reconcile(batch.map { it.identity.toDto() })
            response.results.forEach { result ->
                val identity = Identity(result.name, result.createdOn, result.size)
                val item = byIdentity[identity] ?: return@forEach
                if (result.alreadySynced) {
                    syncedCacheDao.upsert(item.identity.toSyncedCache(SyncStatus.SYNCED))
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
        return notSynced
    }

    // --- Sessions + upload ----------------------------------------------------

    private suspend fun openSession(profileId: String): String =
        api.openSession(OpenSessionRequest(profileId)).sessionId

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
        var failed = 0
        outcomes.forEach { outcome ->
            val pending = pendingUploadByFileId(outcome.fileId) ?: return@forEach
            val identity = Identity(pending.name, pending.createdOn, pending.size)
            if (outcome.status == OUTCOME_SYNCED) {
                syncedCacheDao.upsert(identity.toSyncedCache(SyncStatus.SYNCED))
                failureDao.clear(identity.name, identity.createdOn, identity.size)
                pendingUploadDao.delete(outcome.fileId)
            } else {
                failed += 1
                recordFailure(outcome.fileId, identity, outcome.reason, outcome.retryable)
            }
        }
        _progress.value = _progress.value.copy(failedFiles = _progress.value.failedFiles + failed)
    }

    private suspend fun recordFailure(
        fileId: String,
        identity: Identity,
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
        syncedCacheDao.upsert(identity.toSyncedCache(SyncStatus.FAILED))
        if (retryable) {
            // Leave it in the pending queue (reset to PENDING) for the next run.
            pendingUploadDao.setStatus(fileId, SyncStatus.PENDING.name)
        } else {
            pendingUploadDao.delete(fileId)
        }
    }

    /** Look up a persisted pending row by its file id. */
    private suspend fun pendingUploadByFileId(fileId: String): PendingUploadEntity? =
        pendingUploadDao.pending().firstOrNull { it.fileId == fileId }

    private fun advanceWatermark() {
        val generation = scanner.currentGeneration()
        if (generation > 0) securePrefs.setMediaGeneration(generation)
    }

    // --- Mapping helpers ------------------------------------------------------

    private fun Identity.toDto() = IdentityDto(name = name, createdOn = createdOn, size = size)

    private fun Identity.toSyncedCache(status: SyncStatus) = SyncedCacheEntity(
        name = name,
        createdOn = createdOn,
        size = size,
        status = status.name,
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
        private const val OUTCOME_SYNCED = "synced"
    }
}

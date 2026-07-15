package imagesorter.sync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file queued for (or mid-) upload. Persisted so the engine can resume across
 * app restarts: on resume, the uploader re-reads [serverOffset] (or re-queries
 * the server) and continues the same [fileId] within [sessionId].
 *
 * [mediaStoreId] re-resolves the content URI; the identity columns are the
 * authoritative metadata sent with each chunk.
 */
@Entity(tableName = "pending_upload")
data class PendingUploadEntity(
    @PrimaryKey val fileId: String,
    val mediaStoreId: Long,
    val name: String,
    val createdOn: String,
    val size: Long,
    val mimeType: String,
    /** Session this file belongs to; null until a session has been opened. */
    val sessionId: String? = null,
    /** Last known server-confirmed byte offset (resume point). */
    val serverOffset: Long = 0,
    /** Stored [imagesorter.sync.domain.model.SyncStatus] name. */
    val status: String,
    /** Newest-first ordering key (typically created_on epoch millis). */
    val sortKey: Long = 0,
)

package imagesorter.sync.data.db.entity

import androidx.room.Entity

/**
 * A recorded per-file failure, keyed by identity. [retryable] mirrors the server
 * taxonomy so the engine knows whether to re-queue without re-deriving it.
 */
@Entity(tableName = "failures", primaryKeys = ["name", "createdOn", "size"])
data class FailureEntity(
    val name: String,
    val createdOn: String,
    val size: Long,
    /** Stored [imagesorter.sync.domain.model.FailureReason] name. */
    val reason: String,
    val retryable: Boolean,
    /** Human-readable detail from the server outcome, if any. */
    val message: String? = null,
    val failedAt: Long,
    /** True once this failure has been reported to the server's error log. */
    val reported: Boolean = false,
)

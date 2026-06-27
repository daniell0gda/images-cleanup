package eu.caiq.imagesorter.sync.data.db.entity

import androidx.room.Entity

/**
 * Local cache of server sync truth, keyed by the global identity
 * (name + created_on + size). Rebuildable by re-running reconcile; never the
 * source of truth. Drives the read-only status view and the cleanup candidate
 * set ([lastVerifiedAt] records the most recent successful `/verify`).
 */
@Entity(tableName = "synced_cache", primaryKeys = ["name", "createdOn", "size"])
data class SyncedCacheEntity(
    val name: String,
    val createdOn: String,
    val size: Long,
    /** Stored [eu.caiq.imagesorter.sync.domain.model.SyncStatus] name. */
    val status: String,
    /** Local `MediaStore._ID`, used to load the thumbnail; null if not yet known. */
    val mediaStoreId: Long? = null,
    /** MIME type, used to pick the image vs video MediaStore collection. */
    val mimeType: String? = null,
    /** Epoch millis the server last confirmed presence, or null. */
    val lastVerifiedAt: Long? = null,
    /** Epoch millis this row was last marked synced, or null. */
    val syncedAt: Long? = null,
)

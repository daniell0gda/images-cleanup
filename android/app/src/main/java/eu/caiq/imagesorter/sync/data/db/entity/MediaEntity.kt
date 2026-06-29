package eu.caiq.imagesorter.sync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of one server timeline item, mirroring `GET /api/media` items.
 * Rebuildable from the server; never the source of truth. [orderKey] preserves
 * the server keyset order `(date_taken DESC, id DESC)` so the Room PagingSource
 * can serve the cached timeline in the same order before the network refreshes.
 */
@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey val id: Long,
    val kind: String,
    val dateTaken: String,
    val width: Int? = null,
    val height: Int? = null,
    /** Monotonic insertion order across pages; lower = newer (matches server order). */
    val orderKey: Long,
)

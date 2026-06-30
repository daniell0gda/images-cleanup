package eu.caiq.imagesorter.sync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the opaque keyset cursors for the next `/api/media`
 * load in both directions, plus the next [orderKey] to assign in each direction.
 * Replaced wholesale on REFRESH and advanced on APPEND / PREPEND.
 *
 * - [nextCursor] / [nextOrderKey]: APPEND (load-older) cursor and next ascending
 *   orderKey; a null [nextCursor] means the timeline end (oldest) was reached.
 * - [prevCursor] / [prevOrderKey]: PREPEND (load-newer) cursor and next descending
 *   orderKey (starts at -1 and decreases so newer items sort above index 0); a null
 *   [prevCursor] means the latest has been reached upward.
 */
@Entity(tableName = "media_remote_key")
data class MediaRemoteKey(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nextCursor: String?,
    val nextOrderKey: Long,
    val prevCursor: String?,
    val prevOrderKey: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

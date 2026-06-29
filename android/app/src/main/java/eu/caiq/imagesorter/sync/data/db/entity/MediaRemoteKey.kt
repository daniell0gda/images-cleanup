package eu.caiq.imagesorter.sync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the opaque keyset cursor for the next `/api/media`
 * append, plus the next [orderKey] to assign. Replaced wholesale on REFRESH and
 * advanced on APPEND. A null [nextCursor] means the timeline end was reached.
 */
@Entity(tableName = "media_remote_key")
data class MediaRemoteKey(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nextCursor: String?,
    val nextOrderKey: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

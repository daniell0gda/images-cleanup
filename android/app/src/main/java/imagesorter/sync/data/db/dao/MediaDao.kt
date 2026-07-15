package imagesorter.sync.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import imagesorter.sync.data.db.entity.MediaEntity
import imagesorter.sync.data.db.entity.MediaRemoteKey

@Dao
interface MediaDao {

    /**
     * Cached timeline as a Room-backed [PagingSource], ordered to match the
     * server keyset `(date_taken DESC, id DESC)` via [MediaEntity.orderKey].
     * Drives the Room cache path: pre-populated rows are served without a network
     * load, then RemoteMediator refreshes.
     *
     * Scoped to the active [profile] filter (null = the unfiltered all-profiles view)
     * so rows cached under one filter never leak into another's view.
     */
    @Query("SELECT * FROM media WHERE profile IS :profile ORDER BY orderKey ASC")
    fun pagingSource(profile: String?): PagingSource<Int, MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media")
    suspend fun clear()

    /** Drops a single cached row so its deletion leaves the timeline immediately. */
    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM media_remote_key WHERE id = 0")
    suspend fun remoteKey(): MediaRemoteKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRemoteKey(key: MediaRemoteKey)

    @Query("DELETE FROM media_remote_key")
    suspend fun clearRemoteKey()

    /** Row count scoped to the active [profile] filter (null = all-profiles view). */
    @Query("SELECT COUNT(*) FROM media WHERE profile IS :profile")
    suspend fun count(profile: String?): Int
}

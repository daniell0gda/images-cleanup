package eu.caiq.imagesorter.sync.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.db.entity.MediaRemoteKey

@Dao
interface MediaDao {

    /**
     * Cached timeline as a Room-backed [PagingSource], ordered to match the
     * server keyset `(date_taken DESC, id DESC)` via [MediaEntity.orderKey].
     * Drives the Room cache path: pre-populated rows are served without a network
     * load, then RemoteMediator refreshes.
     */
    @Query("SELECT * FROM media ORDER BY orderKey ASC")
    fun pagingSource(): PagingSource<Int, MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media")
    suspend fun clear()

    @Query("SELECT * FROM media_remote_key WHERE id = 0")
    suspend fun remoteKey(): MediaRemoteKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRemoteKey(key: MediaRemoteKey)

    @Query("DELETE FROM media_remote_key")
    suspend fun clearRemoteKey()

    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int
}

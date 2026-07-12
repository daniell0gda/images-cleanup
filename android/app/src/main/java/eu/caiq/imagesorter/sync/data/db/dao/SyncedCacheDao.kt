package eu.caiq.imagesorter.sync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SyncedCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncedCacheEntity)

    @Query("SELECT * FROM synced_cache ORDER BY syncedAt DESC")
    fun observeAll(): Flow<List<SyncedCacheEntity>>

    @Query("SELECT * FROM synced_cache WHERE status = :status ORDER BY syncedAt DESC")
    fun observeByStatus(status: String): Flow<List<SyncedCacheEntity>>

    /** All rows currently marked synced — the candidate set for cleanup verify. */
    @Query("SELECT * FROM synced_cache WHERE status = 'SYNCED'")
    suspend fun syncedItems(): List<SyncedCacheEntity>

    /**
     * Rows the user has reviewed/the server discarded as "not people". The server
     * reports these already_synced=false forever, so reconcile uses this set to
     * skip re-queuing them for upload.
     */
    @Query("SELECT * FROM synced_cache WHERE status = 'UNCLASSIFIED'")
    suspend fun unclassifiedItems(): List<SyncedCacheEntity>

    @Query(
        "UPDATE synced_cache SET lastVerifiedAt = :verifiedAt " +
            "WHERE name = :name AND createdOn = :createdOn AND size = :size",
    )
    suspend fun markVerified(name: String, createdOn: String, size: Long, verifiedAt: Long)

    @Query(
        "DELETE FROM synced_cache " +
            "WHERE name = :name AND createdOn = :createdOn AND size = :size",
    )
    suspend fun delete(name: String, createdOn: String, size: Long)

    /**
     * Prune rows for locally-deleted media by MediaStore id, regardless of status,
     * so a tile removed from the grid leaves the cache immediately — not only the
     * UNCLASSIFIED "not people" path.
     */
    @Query("DELETE FROM synced_cache WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>)

    /** Distinct non-null MediaStore ids currently cached — the reconcile pass diffs
     *  these against the device enumeration to prune rows for locally-deleted media. */
    @Query("SELECT DISTINCT mediaStoreId FROM synced_cache WHERE mediaStoreId IS NOT NULL")
    suspend fun allMediaStoreIds(): List<Long>
}

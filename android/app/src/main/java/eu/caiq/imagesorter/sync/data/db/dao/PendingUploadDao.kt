package eu.caiq.imagesorter.sync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingUploadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<PendingUploadEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingUploadEntity)

    /** Pending work, newest-first (highest [PendingUploadEntity.sortKey] first). */
    @Query("SELECT * FROM pending_upload WHERE status != 'SYNCED' ORDER BY sortKey DESC")
    suspend fun pending(): List<PendingUploadEntity>

    @Query("SELECT * FROM pending_upload ORDER BY sortKey DESC")
    fun observeAll(): Flow<List<PendingUploadEntity>>

    @Query("UPDATE pending_upload SET sessionId = :sessionId WHERE fileId = :fileId")
    suspend fun setSession(fileId: String, sessionId: String)

    @Query("UPDATE pending_upload SET serverOffset = :offset WHERE fileId = :fileId")
    suspend fun setOffset(fileId: String, offset: Long)

    @Query("UPDATE pending_upload SET status = :status WHERE fileId = :fileId")
    suspend fun setStatus(fileId: String, status: String)

    @Query("DELETE FROM pending_upload WHERE fileId = :fileId")
    suspend fun delete(fileId: String)

    /** Remove any pending/in-progress row for an identity (e.g. when reconcile reports it already synced). */
    @Query("DELETE FROM pending_upload WHERE name = :name AND createdOn = :createdOn AND size = :size")
    suspend fun deleteByIdentity(name: String, createdOn: String, size: Long)

    /** Remove any queued row for locally-deleted media by MediaStore id. */
    @Query("DELETE FROM pending_upload WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>)

    @Query("DELETE FROM pending_upload")
    suspend fun clear()
}

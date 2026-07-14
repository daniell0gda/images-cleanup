package eu.caiq.imagesorter.sync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import eu.caiq.imagesorter.sync.data.db.entity.FailureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FailureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FailureEntity)

    @Query("SELECT * FROM failures ORDER BY failedAt DESC")
    fun observeAll(): Flow<List<FailureEntity>>

    @Query("SELECT * FROM failures WHERE retryable = 1")
    suspend fun retryable(): List<FailureEntity>

    @Query("SELECT * FROM failures WHERE reported = 0")
    suspend fun unreported(): List<FailureEntity>

    @Query(
        "UPDATE failures SET reported = 1 " +
            "WHERE name = :name AND createdOn = :createdOn AND size = :size",
    )
    suspend fun markReported(name: String, createdOn: String, size: Long)

    @Query(
        "DELETE FROM failures " +
            "WHERE name = :name AND createdOn = :createdOn AND size = :size",
    )
    suspend fun clear(name: String, createdOn: String, size: Long)
}

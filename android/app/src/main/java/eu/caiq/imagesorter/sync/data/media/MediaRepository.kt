package eu.caiq.imagesorter.sync.data.media

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

/**
 * Entry point for the server media timeline. Exposes a Room-backed
 * `Flow<PagingData<MediaEntity>>` driven by [MediaRemoteMediator]: the cached
 * timeline serves immediately on restart and the network refreshes it.
 *
 * Cluster 10 consumes [timeline] (and applies [insertDayHeaders] for headers).
 */
@OptIn(ExperimentalPagingApi::class)
class MediaRepository(
    private val api: MediaApi,
    private val db: AppDatabase,
    private val pageSize: Int = MediaRemoteMediator.DEFAULT_PAGE_SIZE,
) {
    fun timeline(): Flow<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            remoteMediator = MediaRemoteMediator(api, db, pageSize),
            pagingSourceFactory = { db.mediaDao().pagingSource() },
        ).flow
}

package eu.caiq.imagesorter.sync.data.media

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.db.entity.MediaRemoteKey

/**
 * Loads `/api/media` keyset pages into the Room [media] cache.
 *
 * REFRESH fetches the first page (cursor=null) and replaces the cache atomically
 * (clear + insert + store the next cursor). APPEND fetches the next page using the
 * stored opaque cursor; PREPEND is unsupported (newest-first, server-driven).
 * `endOfPaginationReached` is true once the server returns `next_cursor == null`.
 *
 * The Room PagingSource is the source of truth for the UI, so a restart serves the
 * cached timeline immediately and this mediator refreshes it from the network.
 */
@OptIn(ExperimentalPagingApi::class)
class MediaRemoteMediator(
    private val api: MediaApi,
    private val db: AppDatabase,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : RemoteMediator<Int, MediaEntity>() {

    private val dao = db.mediaDao()

    /**
     * On a restart with a populated cache, skip the automatic initial refresh so
     * the Room-backed timeline is served first; an explicit refresh (pull / launch
     * policy) still goes to the network. An empty cache triggers a normal refresh.
     */
    override suspend fun initialize(): InitializeAction =
        if (dao.remoteKey() != null) {
            InitializeAction.SKIP_INITIAL_REFRESH
        } else {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MediaEntity>,
    ): MediatorResult {
        return try {
            when (loadType) {
                LoadType.PREPEND ->
                    MediatorResult.Success(endOfPaginationReached = true)

                LoadType.REFRESH -> {
                    val page = api.media(cursor = null, limit = pageSize)
                    db.withTransaction {
                        dao.clear()
                        dao.clearRemoteKey()
                        insertPage(page.items, startOrderKey = 0)
                        dao.setRemoteKey(
                            MediaRemoteKey(
                                nextCursor = page.nextCursor,
                                nextOrderKey = page.items.size.toLong(),
                            ),
                        )
                    }
                    MediatorResult.Success(endOfPaginationReached = page.nextCursor == null)
                }

                LoadType.APPEND -> {
                    val key = dao.remoteKey()
                    val cursor = key?.nextCursor
                        ?: return MediatorResult.Success(endOfPaginationReached = true)
                    val page = api.media(cursor = cursor, limit = pageSize)
                    db.withTransaction {
                        insertPage(page.items, startOrderKey = key.nextOrderKey)
                        dao.setRemoteKey(
                            MediaRemoteKey(
                                nextCursor = page.nextCursor,
                                nextOrderKey = key.nextOrderKey + page.items.size,
                            ),
                        )
                    }
                    MediatorResult.Success(endOfPaginationReached = page.nextCursor == null)
                }
            }
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun insertPage(items: List<MediaItemDto>, startOrderKey: Long) {
        dao.insertAll(
            items.mapIndexed { index, dto ->
                MediaEntity(
                    id = dto.id,
                    kind = dto.kind,
                    dateTaken = dto.dateTaken,
                    width = dto.width,
                    height = dto.height,
                    orderKey = startOrderKey + index,
                )
            },
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
    }
}

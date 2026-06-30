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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Emitted when a date-seek REFRESH finishes writing its page to Room. [generation]
 * increments per completed seek so a waiter can detect "this seek is done"; [mediaWritten]
 * is the exact number of media rows this REFRESH wrote (the on-or-before page plus any
 * eager-loaded newer photos). The UI waits until the snapshot's media count equals
 * [mediaWritten] before anchoring — that count is unique to this seek, so it is not
 * satisfied by a previous seek's stale (and also negative-orderKey'd) data.
 */
data class SeekRefreshSignal(val generation: Int, val mediaWritten: Int)

/**
 * Loads `/api/media` keyset pages into the Room [media] cache.
 *
 * REFRESH fetches the first page (cursor=null) and replaces the cache atomically
 * (clear + insert + store both directional cursors). APPEND fetches older photos via
 * the stored `nextCursor`; PREPEND fetches newer photos via the stored `prevCursor`
 * (`before=`), assigning decreasing orderKeys so newer items sort above index 0.
 * After a seek, scrolling up walks PREPEND pages until `prev_cursor == null` (latest).
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
     * One-shot date seek. When non-null, the next REFRESH passes it as `from_date`
     * (server starts the page at the newest photo on or before that date) and the
     * cursor is cleared immediately, so a subsequent refresh returns to newest-first.
     */
    val seekCursor = AtomicReference<String?>(null)

    private val _seekRefresh = MutableStateFlow(SeekRefreshSignal(generation = 0, mediaWritten = 0))

    /** Signals each completed date-seek REFRESH so the UI can anchor only once its rows land. */
    val seekRefresh: StateFlow<SeekRefreshSignal> = _seekRefresh.asStateFlow()

    /**
     * On a restart with a populated cache, skip the automatic initial refresh so
     * the Room-backed timeline is served first; an explicit refresh (pull / launch
     * policy) still goes to the network. An empty cache triggers a normal refresh.
     *
     * A remote key can exist with 0 media rows when the server previously returned
     * an empty index (e.g. the build had not run yet). In that case there is nothing
     * worth caching, so still launch a refresh rather than staying stuck on empty.
     */
    override suspend fun initialize(): InitializeAction =
        if (dao.remoteKey() != null && dao.count() > 0) {
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
                LoadType.PREPEND -> {
                    val key = dao.remoteKey()
                    val before = key?.prevCursor
                        ?: return MediatorResult.Success(endOfPaginationReached = true)
                    val page = api.media(before = before, limit = pageSize)
                    // Items arrive ASCENDING (closest-newer first .. newest last). Assign
                    // decreasing orderKeys so the newest ends up most-negative and sorts on top.
                    val startKey = key.prevOrderKey
                    db.withTransaction {
                        dao.insertAll(
                            page.items.mapIndexed { index, dto ->
                                MediaEntity(
                                    id = dto.id,
                                    kind = dto.kind,
                                    dateTaken = dto.dateTaken,
                                    width = dto.width,
                                    height = dto.height,
                                    orderKey = startKey - index,
                                )
                            },
                        )
                        dao.setRemoteKey(
                            key.copy(
                                prevCursor = page.prevCursor,
                                prevOrderKey = startKey - page.items.size,
                            ),
                        )
                    }
                    MediatorResult.Success(endOfPaginationReached = page.prevCursor == null)
                }

                LoadType.REFRESH -> {
                    val fromDate = seekCursor.getAndSet(null)
                    val page = api.media(cursor = null, limit = pageSize, fromDate = fromDate)
                    // After a seek, eagerly pull ONE page of newer photos above the anchor so
                    // it lands off the prepend edge; without it the anchor sits at index 0 and
                    // Paging cascades PREPEND back to latest. Skip for non-seek refresh (no
                    // newer photos exist) and when the seek already landed on the latest page.
                    val newer = if (fromDate != null && page.prevCursor != null) {
                        api.media(before = page.prevCursor, limit = pageSize)
                    } else {
                        null
                    }
                    db.withTransaction {
                        dao.clear()
                        dao.clearRemoteKey()
                        insertPage(page.items, startOrderKey = 0)
                        // newer items arrive ASCENDING (closest-newer .. newest last); assign
                        // decreasing orderKeys so the newest is most-negative and sorts on top.
                        newer?.items?.forEachIndexed { index, dto ->
                            dao.insertAll(listOf(toEntity(dto, orderKey = -1L - index)))
                        }
                        dao.setRemoteKey(
                            MediaRemoteKey(
                                nextCursor = page.nextCursor,
                                nextOrderKey = page.items.size.toLong(),
                                // When an eager newer-load ran, trust ITS prevCursor even when null
                                // (null = top reached, so PREPEND must stop). Only fall back to the
                                // anchor page's prevCursor when no eager load happened, otherwise
                                // PREPEND re-fetches the newer photos we just inserted.
                                prevCursor = if (newer != null) newer.prevCursor else page.prevCursor,
                                prevOrderKey = -1L - (newer?.items?.size ?: 0),
                            ),
                        )
                    }
                    if (fromDate != null) {
                        _seekRefresh.value = SeekRefreshSignal(
                            generation = _seekRefresh.value.generation + 1,
                            mediaWritten = page.items.size + (newer?.items?.size ?: 0),
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
                            key.copy(
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
        dao.insertAll(items.mapIndexed { index, dto -> toEntity(dto, startOrderKey + index) })
    }

    private fun toEntity(dto: MediaItemDto, orderKey: Long) = MediaEntity(
        id = dto.id,
        kind = dto.kind,
        dateTaken = dto.dateTaken,
        width = dto.width,
        height = dto.height,
        orderKey = orderKey,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
    }
}

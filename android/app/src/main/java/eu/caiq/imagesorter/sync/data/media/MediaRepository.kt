package eu.caiq.imagesorter.sync.data.media

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entry point for the server media timeline. Exposes a Room-backed
 * `Flow<PagingData<MediaEntity>>` driven by [MediaRemoteMediator]: the cached
 * timeline serves immediately on restart and the network refreshes it.
 *
 * Date navigation: [seekToDate] arms a one-shot `from_date` cursor on the mediator;
 * [resetToLatest] clears it. Both only arm state — the caller must then trigger a
 * paging refresh (`LazyPagingItems.refresh()`) so the mediator's REFRESH re-runs and
 * consumes the cursor. [isSeekActive] tells the UI whether the view is a seek result.
 */
@OptIn(ExperimentalPagingApi::class)
class MediaRepository(
    private val api: MediaApi,
    private val db: AppDatabase,
    private val pageSize: Int = MediaRemoteMediator.DEFAULT_PAGE_SIZE,
) {
    private val mediator = MediaRemoteMediator(api, db, pageSize)
    private val seekActive = MutableStateFlow(false)

    /**
     * The auto-refresh floor for the Photos timeline. Owned here (singleton) so the 30s throttle
     * survives tab switches and rotation — the UI reads it from the loop and the manual pull.
     */
    val refreshThrottle = RefreshThrottle()

    fun timeline(): Flow<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            remoteMediator = mediator,
            pagingSourceFactory = { db.mediaDao().pagingSource() },
        ).flow

    /**
     * A bounded, server-backed timeline for a single date segment. Independent of [timeline]:
     * it uses [MediaSegmentPagingSource] (no mediator, no Room cache, no seek state), so entering
     * or leaving segment mode never disturbs the newest-first timeline's REFRESH/APPEND/PREPEND
     * machinery. [fromDate] is the segment's latest day (page start) and [datePrefix] the ISO
     * prefix every in-segment capture date shares (`"2024-"`, `"2024-03-"`, `"2024-03-10"`).
     */
    fun segmentTimeline(fromDate: String, datePrefix: String): Flow<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { MediaSegmentPagingSource(api, fromDate, datePrefix) },
        ).flow

    /** Arms a one-shot `from_date` seek; the caller must then refresh the pager. */
    suspend fun seekToDate(date: String) {
        mediator.seekCursor.set(date)
        seekActive.value = true
    }

    /** Clears any seek; the caller must then refresh the pager back to newest-first. */
    suspend fun resetToLatest() {
        mediator.seekCursor.set(null)
        seekActive.value = false
    }

    /** True while the timeline reflects a date seek rather than newest-first. */
    fun isSeekActive(): StateFlow<Boolean> = seekActive.asStateFlow()

    /** Signals each completed date-seek REFRESH so the UI can anchor only once its rows land. */
    fun seekRefresh(): StateFlow<SeekRefreshSignal> = mediator.seekRefresh

    /** The server's year → month → day tree of dates that have indexed photos. */
    suspend fun availableDates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = api.dates()
}

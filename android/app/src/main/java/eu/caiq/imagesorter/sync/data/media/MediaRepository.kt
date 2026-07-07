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
            config = PagingConfig(
                pageSize = pageSize,
                // Left at the default (== pageSize), landing a seek's anchor within this many
                // items of the loaded edge auto-fires PREPEND with no user scroll — since the
                // seek only eager-loads SEEK_PAGE_SIZE (50) newer items above the anchor, that
                // silently cascaded PREPEND page after page toward latest. Keep this well below
                // SEEK_PAGE_SIZE so nothing auto-fires at rest; real upward scrolling still
                // triggers it once the user nears the loaded edge.
                prefetchDistance = SEEK_PREFETCH_DISTANCE,
                // Left at the default (pageSize * 3), Paging tries to backfill the initial load
                // up to that size, triggering extra automatic PREPEND calls beyond our own
                // deliberate one to make up the difference. Match it to what a REFRESH actually
                // supplies so nothing extra is requested purely to satisfy this target.
                initialLoadSize = pageSize,
                enablePlaceholders = false,
            ),
            remoteMediator = mediator,
            pagingSourceFactory = { db.mediaDao().pagingSource() },
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

    companion object {
        /** Below [MediaRemoteMediator.SEEK_PAGE_SIZE] so a landed seek never auto-fires PREPEND. */
        private const val SEEK_PREFETCH_DISTANCE = 20
    }
}

package eu.caiq.imagesorter.sync.media

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.RemoteMediator.InitializeAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaPageDto
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.db.entity.MediaRemoteKey
import eu.caiq.imagesorter.sync.data.media.MediaRemoteMediator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records calls and returns a queued page per cursor. */
private class FakeMediaApi(private val pages: List<MediaPageDto>) : MediaApi {
    val cursors = mutableListOf<String?>()
    val fromDates = mutableListOf<String?>()
    val befores = mutableListOf<String?>()
    private var index = 0
    override suspend fun media(cursor: String?, limit: Int?, fromDate: String?, before: String?): MediaPageDto {
        cursors.add(cursor)
        fromDates.add(fromDate)
        befores.add(before)
        return pages[index++]
    }

    override suspend fun dates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = emptyMap()
}

@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaRemoteMediatorTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun emptyState() = PagingState<Int, MediaEntity>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 10),
        leadingPlaceholderCount = 0,
    )

    private fun item(id: Long, day: String) =
        MediaItemDto(id = id, kind = "image", dateTaken = "${day}T00:00:00")

    @Test
    fun refreshReplacesCacheAndStoresNextCursor() = runTest {
        val api = FakeMediaApi(listOf(MediaPageDto(listOf(item(30, "2024-03-03"), item(20, "2024-03-02")), nextCursor = "c1")))
        // Pre-existing stale row that REFRESH must clear.
        db.mediaDao().insertAll(listOf(MediaEntity(99, "image", "2000-01-01T00:00:00", orderKey = 0)))
        val mediator = MediaRemoteMediator(api, db)

        val result = mediator.load(LoadType.REFRESH, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertNull(api.cursors.single()) // first page fetched with cursor=null
        val cached = loadCached()
        assertEquals(listOf(30L, 20L), cached.map { it.id }) // stale row gone, ordered
        assertEquals("c1", db.mediaDao().remoteKey()?.nextCursor)
    }

    @Test
    fun refreshUsesSeekCursorWhenSetThenClearsItAfterOneUse() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1"),
                MediaPageDto(listOf(item(40, "2024-04-01")), nextCursor = "c2"),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.seekCursor.set("2024-03-03")

        mediator.load(LoadType.REFRESH, emptyState())
        // Second refresh must fall back to null — the seek cursor is one-shot.
        mediator.load(LoadType.REFRESH, emptyState())

        assertEquals(listOf("2024-03-03", null), api.fromDates)
    }

    @Test
    fun refreshFallsBackToNullFromDateWhenNoSeekCursorSet() = runTest {
        val api = FakeMediaApi(listOf(MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = null)))
        val mediator = MediaRemoteMediator(api, db)

        mediator.load(LoadType.REFRESH, emptyState())

        assertNull(api.fromDates.single())
    }

    @Test
    fun appendUsesStoredCursorAndReportsEndWhenNullCursor() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1"),
                MediaPageDto(listOf(item(20, "2024-03-02")), nextCursor = null),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.load(LoadType.REFRESH, emptyState())

        val result = mediator.load(LoadType.APPEND, emptyState())

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(listOf(null, "c1"), api.cursors) // append used the stored cursor
        assertEquals(listOf(30L, 20L), loadCached().map { it.id }) // appended after, ordered
        assertNull(db.mediaDao().remoteKey()?.nextCursor) // end reached
    }

    @Test
    fun skipsInitialRefreshWhenCachePopulatedSoRestartServesCacheFirst() = runTest {
        val mediator = MediaRemoteMediator(FakeMediaApi(emptyList()), db)

        // Empty cache → refresh from network on first launch.
        assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())

        // Remote key present but no rows (previous run fetched 0 items from server) →
        // still refresh so new server items become visible without reinstalling.
        db.mediaDao().setRemoteKey(MediaRemoteKey(nextCursor = null, nextOrderKey = 0, prevCursor = null, prevOrderKey = -1))
        assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())

        // Remote key + actual rows → serve cache before refreshing.
        db.mediaDao().insertAll(listOf(MediaEntity(1, "image", "2024-01-01T00:00:00", orderKey = 0)))
        assertEquals(InitializeAction.SKIP_INITIAL_REFRESH, mediator.initialize())
    }

    @Test
    fun prependReturnsEndWhenNoPrevCursorStored() = runTest {
        val api = FakeMediaApi(
            listOf(MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = null)),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.load(LoadType.REFRESH, emptyState())
        api.befores.clear()
        api.cursors.clear()

        val result = mediator.load(LoadType.PREPEND, emptyState())

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue("PREPEND with no prev cursor must not hit the network", api.befores.isEmpty())
    }

    @Test
    fun prependRequestsServerWithBeforeEqualToStoredPrevCursor() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                MediaPageDto(listOf(item(40, "2024-03-04")), nextCursor = null, prevCursor = null),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.load(LoadType.REFRESH, emptyState())

        mediator.load(LoadType.PREPEND, emptyState())

        assertEquals("p1", api.befores.last())
    }

    @Test
    fun prependLoadsNewerItemsWithDecreasingOrderKeysNewestOnTop() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                // ASCENDING: closest-newer .. newest last
                MediaPageDto(
                    listOf(item(40, "2024-03-04"), item(50, "2024-03-05"), item(60, "2024-03-06")),
                    nextCursor = null,
                    prevCursor = "p2",
                ),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.load(LoadType.REFRESH, emptyState())

        mediator.load(LoadType.PREPEND, emptyState())

        // Ordered by orderKey ASC: newest (60, orderKey -3) is at the top, then 50, 40, then refresh item 30.
        assertEquals(listOf(60L, 50L, 40L, 30L), loadCached().map { it.id })
    }

    @Test
    fun prependUpdatesPrevCursorFromResponseAndReportsEndWhenNull() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                MediaPageDto(listOf(item(40, "2024-03-04")), nextCursor = null, prevCursor = null),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.load(LoadType.REFRESH, emptyState())

        val result = mediator.load(LoadType.PREPEND, emptyState())

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertNull(db.mediaDao().remoteKey()?.prevCursor)
        // next-direction state preserved
        assertEquals("c1", db.mediaDao().remoteKey()?.nextCursor)
    }

    @Test
    fun appendPreservesPrevCursor() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                MediaPageDto(listOf(item(20, "2024-03-02")), nextCursor = "c2", prevCursor = null),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.load(LoadType.REFRESH, emptyState())

        mediator.load(LoadType.APPEND, emptyState())

        assertEquals("p1", db.mediaDao().remoteKey()?.prevCursor)
        assertEquals(-1L, db.mediaDao().remoteKey()?.prevOrderKey)
    }

    @Test
    fun seekRefreshEagerLoadsOnePageOfNewerAboveAnchorAndPreservesPrevState() = runTest {
        val api = FakeMediaApi(
            listOf(
                // from_date page: anchor item + non-null prev_cursor (newer photos exist)
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                // eager newer page (ascending: closest-newer .. newest last), still more newer above
                MediaPageDto(
                    listOf(item(40, "2024-03-04"), item(50, "2024-03-05")),
                    nextCursor = null,
                    prevCursor = "p2",
                ),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.seekCursor.set("2024-03-03")

        mediator.load(LoadType.REFRESH, emptyState())

        // Eager newer page sits ABOVE the anchor at negative orderKeys (newest on top).
        assertEquals(listOf(50L, 40L, 30L), loadCached().map { it.id })
        // Anchor stays at orderKey 0; eager newer items at -1, -2.
        assertEquals(0L, loadCached().first { it.id == 30L }.orderKey)
        assertTrue(loadCached().filter { it.id != 30L }.all { it.orderKey < 0L })
        // prev-state advanced from the eager page so scrolling UP still PREPENDs more newer.
        val key = db.mediaDao().remoteKey()
        assertEquals("p2", key?.prevCursor)
        assertEquals(-3L, key?.prevOrderKey)
    }

    @Test
    fun singlePrependAfterSeekLoadsExactlyOnePageOfNewerAndDoesNotCascadeToLatest() = runTest {
        val api = FakeMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                // eager newer page (still more newer above: prevCursor "p2")
                MediaPageDto(listOf(item(40, "2024-03-04")), nextCursor = null, prevCursor = "p2"),
                // one further newer page reached by a single PREPEND (more still above: "p3")
                MediaPageDto(listOf(item(50, "2024-03-05")), nextCursor = null, prevCursor = "p3"),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.seekCursor.set("2024-03-03")
        mediator.load(LoadType.REFRESH, emptyState())
        // REFRESH made exactly 2 calls (from_date page + one eager newer page).
        assertEquals(2, api.cursors.size)
        api.befores.clear()

        mediator.load(LoadType.PREPEND, emptyState())

        // Exactly one more network call — the single PREPEND did not walk to latest.
        assertEquals(3, api.cursors.size)
        assertEquals("p2", api.befores.single()) // used the eager page's prev cursor
        // The one newer page sits above all prior items at a further-decreasing orderKey.
        assertEquals(listOf(50L, 40L, 30L), loadCached().map { it.id })
        assertEquals("p3", db.mediaDao().remoteKey()?.prevCursor)
    }

    @Test
    fun seekRefreshOnLatestPageSkipsEagerLoadAndPrependReportsEdgeReached() = runTest {
        val api = FakeMediaApi(
            // from_date page IS the latest page: prev_cursor null → no newer photos exist.
            listOf(MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = null)),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.seekCursor.set("2024-03-03")
        mediator.load(LoadType.REFRESH, emptyState())

        // No eager newer fetch — only the from_date page was requested.
        assertEquals(1, api.cursors.size)
        // Anchor is the only item and sits at the top (orderKey 0, nothing negative above).
        assertEquals(listOf(30L), loadCached().map { it.id })
        assertEquals(0L, loadCached().single().orderKey)
        assertNull(db.mediaDao().remoteKey()?.prevCursor)

        val result = mediator.load(LoadType.PREPEND, emptyState())

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        // PREPEND must not hit the network when no newer photos exist.
        assertEquals(1, api.cursors.size)
    }

    @Test
    fun appendAfterSeekLoadsOlderViaNextCursorAndLeavesPrevStateUntouched() = runTest {
        val api = FakeMediaApi(
            listOf(
                // from_date (anchor) page
                MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1"),
                // eager newer page
                MediaPageDto(listOf(item(40, "2024-03-04")), nextCursor = null, prevCursor = "p2"),
                // APPEND (older) page via stored nextCursor "c1"
                MediaPageDto(listOf(item(20, "2024-03-02")), nextCursor = "c3", prevCursor = "ignored"),
            ),
        )
        val mediator = MediaRemoteMediator(api, db)
        mediator.seekCursor.set("2024-03-03")
        mediator.load(LoadType.REFRESH, emptyState())
        val prevBefore = db.mediaDao().remoteKey()?.prevCursor
        val prevOrderBefore = db.mediaDao().remoteKey()?.prevOrderKey

        mediator.load(LoadType.APPEND, emptyState())

        // APPEND used the stored next cursor.
        assertEquals("c1", api.cursors.last())
        // Older item appended below the anchor with an increasing orderKey (anchor at 0 → 1).
        assertEquals(1L, loadCached().first { it.id == 20L }.orderKey)
        assertEquals("c3", db.mediaDao().remoteKey()?.nextCursor)
        // prev-state (upward seek path) untouched by the downward APPEND.
        assertEquals(prevBefore, db.mediaDao().remoteKey()?.prevCursor)
        assertEquals(prevOrderBefore, db.mediaDao().remoteKey()?.prevOrderKey)
        assertEquals("p2", db.mediaDao().remoteKey()?.prevCursor)
    }

    @Test
    fun refreshStoresPrevCursorAndInitialPrevOrderKey() = runTest {
        val api = FakeMediaApi(
            listOf(MediaPageDto(listOf(item(30, "2024-03-03")), nextCursor = "c1", prevCursor = "p1")),
        )
        val mediator = MediaRemoteMediator(api, db)

        mediator.load(LoadType.REFRESH, emptyState())

        val key = db.mediaDao().remoteKey()
        assertEquals("p1", key?.prevCursor)
        assertEquals(-1L, key?.prevOrderKey)
    }

    private suspend fun loadCached(): List<MediaEntity> {
        val result = db.mediaDao().pagingSource().load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
        )
        return (result as PagingSource.LoadResult.Page).data
    }
}

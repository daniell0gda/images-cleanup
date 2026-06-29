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
    private var index = 0
    override suspend fun media(cursor: String?, limit: Int?): MediaPageDto {
        cursors.add(cursor)
        return pages[index++]
    }
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

        // Prior run left a remote key (and cache) → serve cache before refreshing.
        db.mediaDao().setRemoteKey(MediaRemoteKey(nextCursor = "c1", nextOrderKey = 1))
        assertEquals(InitializeAction.SKIP_INITIAL_REFRESH, mediator.initialize())
    }

    private suspend fun loadCached(): List<MediaEntity> {
        val result = db.mediaDao().pagingSource().load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
        )
        return (result as PagingSource.LoadResult.Page).data
    }
}

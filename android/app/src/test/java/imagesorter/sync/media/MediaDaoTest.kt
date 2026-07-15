package imagesorter.sync.media

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import imagesorter.sync.data.db.AppDatabase
import imagesorter.sync.data.db.entity.MediaEntity
import imagesorter.sync.data.db.entity.MediaRemoteKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaDaoTest {

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

    @Test
    fun pagingSourceServesCachedRowsInOrderKeyOrder() = runTest {
        val dao = db.mediaDao()
        dao.insertAll(
            listOf(
                MediaEntity(id = 30, kind = "image", dateTaken = "2024-03-03T00:00:00", orderKey = 0),
                MediaEntity(id = 20, kind = "video", dateTaken = "2024-03-02T00:00:00", orderKey = 1),
                MediaEntity(id = 10, kind = "image", dateTaken = "2024-03-01T00:00:00", orderKey = 2),
            ),
        )

        val source = dao.pagingSource(profile = null)
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false),
        )

        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf(30L, 20L, 10L), page.data.map { it.id })
    }

    @Test
    fun pagingSourceScopedToProfileServesOnlyMatchingRowsAndNeverLeaks() = runTest {
        val dao = db.mediaDao()
        dao.insertAll(
            listOf(
                MediaEntity(id = 1, kind = "image", dateTaken = "2024-03-03T00:00:00", orderKey = 0, profile = null),
                MediaEntity(id = 2, kind = "image", dateTaken = "2024-03-02T00:00:00", orderKey = 1, profile = "p1"),
                MediaEntity(id = 3, kind = "image", dateTaken = "2024-03-01T00:00:00", orderKey = 2, profile = "p1"),
            ),
        )

        // All-profiles view (null) shows only untagged rows — p1 rows never leak in.
        assertEquals(listOf(1L), scoped(profile = null))
        // A profile filter shows only that profile's rows — the all-profiles row never leaks in.
        assertEquals(listOf(2L, 3L), scoped(profile = "p1"))
        // count() is likewise scoped, so RemoteMediator.initialize refreshes per profile.
        assertEquals(1, dao.count(profile = null))
        assertEquals(2, dao.count(profile = "p1"))
    }

    private suspend fun scoped(profile: String?): List<Long> {
        val result = db.mediaDao().pagingSource(profile).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false),
        )
        return (result as PagingSource.LoadResult.Page).data.map { it.id }
    }

    @Test
    fun clearAndRemoteKeyRoundTrip() = runTest {
        val dao = db.mediaDao()
        dao.insertAll(listOf(MediaEntity(1, "image", "2024-01-01T00:00:00", orderKey = 0)))
        dao.setRemoteKey(MediaRemoteKey(nextCursor = "cur", nextOrderKey = 1, prevCursor = null, prevOrderKey = -1))

        assertEquals("cur", dao.remoteKey()?.nextCursor)

        dao.clear()
        dao.clearRemoteKey()

        val source = dao.pagingSource(profile = null)
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false),
        )
        assertEquals(0, (result as PagingSource.LoadResult.Page).data.size)
        assertNull(dao.remoteKey())
    }
}

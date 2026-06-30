package eu.caiq.imagesorter.sync.media

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.db.entity.MediaRemoteKey
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

        val source = dao.pagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false),
        )

        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf(30L, 20L, 10L), page.data.map { it.id })
    }

    @Test
    fun clearAndRemoteKeyRoundTrip() = runTest {
        val dao = db.mediaDao()
        dao.insertAll(listOf(MediaEntity(1, "image", "2024-01-01T00:00:00", orderKey = 0)))
        dao.setRemoteKey(MediaRemoteKey(nextCursor = "cur", nextOrderKey = 1, prevCursor = null, prevOrderKey = -1))

        assertEquals("cur", dao.remoteKey()?.nextCursor)

        dao.clear()
        dao.clearRemoteKey()

        val source = dao.pagingSource()
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false),
        )
        assertEquals(0, (result as PagingSource.LoadResult.Page).data.size)
        assertNull(dao.remoteKey())
    }
}

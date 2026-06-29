package eu.caiq.imagesorter.sync.media

import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaPageDto
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.db.entity.MediaRemoteKey
import eu.caiq.imagesorter.sync.data.media.MediaRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class QueueMediaApi(private val pages: List<MediaPageDto>) : MediaApi {
    var calls = 0
        private set
    override suspend fun media(cursor: String?, limit: Int?): MediaPageDto =
        pages[calls++]
}

/** Records whether the network was hit; returns a terminal empty page. */
private class TerminalMediaApi : MediaApi {
    var hit = false
        private set
    override suspend fun media(cursor: String?, limit: Int?): MediaPageDto {
        hit = true
        return MediaPageDto(items = emptyList(), nextCursor = null)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaRepositoryTest {

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

    private fun item(id: Long, day: String) =
        MediaItemDto(id = id, kind = "image", dateTaken = "${day}T00:00:00")

    @Test
    fun pagerAppendsNextPageOnScrollTowardEnd() = runTest {
        val api = QueueMediaApi(
            listOf(
                MediaPageDto(listOf(item(30, "2024-03-03"), item(29, "2024-03-03")), nextCursor = "c1"),
                MediaPageDto(listOf(item(20, "2024-03-02")), nextCursor = null),
            ),
        )
        val repo = MediaRepository(api, db, pageSize = 2)

        val ids = repo.timeline().asSnapshot {
            appendScrollWhile { it.id > 0 }
        }.map { it.id }

        assertEquals(listOf(30L, 29L, 20L), ids) // refresh page + appended page
    }

    @Test
    fun restartServesCachedTimelineBeforeNetwork() = runTest {
        // Simulate a prior run that already populated the cache + remote key.
        db.mediaDao().insertAll(
            listOf(
                MediaEntity(30, "image", "2024-03-03T00:00:00", orderKey = 0),
                MediaEntity(20, "image", "2024-03-02T00:00:00", orderKey = 1),
            ),
        )
        db.mediaDao().setRemoteKey(MediaRemoteKey(nextCursor = null, nextOrderKey = 2))
        // On restart the mediator skips the initial refresh (cache + key present),
        // so the Room-backed timeline is served straight from cache. The end-cursor
        // is null, so paging does not fetch from the network at all.
        val api = TerminalMediaApi()
        val repo = MediaRepository(api, db, pageSize = 2)

        val ids = repo.timeline().asSnapshot().map { it.id }

        assertEquals(listOf(30L, 20L), ids)
        assertFalse("cache must serve before any network call", api.hit)
    }
}

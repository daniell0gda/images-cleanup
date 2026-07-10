package eu.caiq.imagesorter.sync.media

import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.api.dto.DeletedDto
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class QueueMediaApi(private val pages: List<MediaPageDto>) : MediaApi {
    var calls = 0
        private set
    override suspend fun media(cursor: String?, limit: Int?, fromDate: String?, before: String?, profile: String?): MediaPageDto =
        pages[calls++]

    override suspend fun dates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = emptyMap()
    override suspend fun deleteMedia(id: Long): DeletedDto = DeletedDto(deleted = true)
}

/** Records the `from_date` of every refresh and the id of every delete; returns terminal pages. */
private class RecordingMediaApi : MediaApi {
    val fromDates = mutableListOf<String?>()
    val deleted = mutableListOf<Long>()
    override suspend fun media(cursor: String?, limit: Int?, fromDate: String?, before: String?, profile: String?): MediaPageDto {
        if (cursor == null && before == null) fromDates.add(fromDate)
        return MediaPageDto(items = emptyList(), nextCursor = null)
    }

    override suspend fun dates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = emptyMap()
    override suspend fun deleteMedia(id: Long): DeletedDto {
        deleted.add(id)
        return DeletedDto(deleted = true)
    }
}

/**
 * Serves a fixed newest-first dataset. `from_date` starts the page at the newest item on or
 * before that date; `cursor` (an index string) continues older. Mirrors the server keyset
 * semantics the segment paging source relies on.
 */
private class DatasetMediaApi(private val all: List<MediaItemDto>) : MediaApi {
    override suspend fun media(cursor: String?, limit: Int?, fromDate: String?, before: String?, profile: String?): MediaPageDto {
        val start = when {
            cursor != null -> cursor.toInt()
            fromDate != null -> all.indexOfFirst { it.dateTaken.substring(0, 10) <= fromDate }.let { if (it < 0) all.size else it }
            else -> 0
        }
        val slice = all.drop(start).take(limit ?: all.size)
        val nextIndex = start + slice.size
        val next = if (nextIndex < all.size) nextIndex.toString() else null
        return MediaPageDto(items = slice, nextCursor = next)
    }

    override suspend fun dates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = emptyMap()
    override suspend fun deleteMedia(id: Long): DeletedDto = DeletedDto(deleted = true)
}

/** Records whether the network was hit; returns a terminal empty page. */
private class TerminalMediaApi : MediaApi {
    var hit = false
        private set
    override suspend fun media(cursor: String?, limit: Int?, fromDate: String?, before: String?, profile: String?): MediaPageDto {
        hit = true
        return MediaPageDto(items = emptyList(), nextCursor = null)
    }

    override suspend fun dates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = emptyMap()
    override suspend fun deleteMedia(id: Long): DeletedDto = DeletedDto(deleted = true)
}

/** Returns profile-specific items and records the profile of every refresh request. */
private class ProfileRecordingApi : MediaApi {
    val profiles = mutableListOf<String?>()
    override suspend fun media(cursor: String?, limit: Int?, fromDate: String?, before: String?, profile: String?): MediaPageDto {
        if (cursor == null && before == null) profiles.add(profile)
        val items = if (profile == "p1") {
            listOf(MediaItemDto(id = 30, kind = "image", dateTaken = "2024-03-03T00:00:00"))
        } else {
            listOf(
                MediaItemDto(id = 30, kind = "image", dateTaken = "2024-03-03T00:00:00"),
                MediaItemDto(id = 20, kind = "image", dateTaken = "2024-03-02T00:00:00"),
            )
        }
        return MediaPageDto(items = items, nextCursor = null)
    }

    override suspend fun dates(): eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto = emptyMap()
    override suspend fun deleteMedia(id: Long): DeletedDto = DeletedDto(deleted = true)
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
        db.mediaDao().setRemoteKey(MediaRemoteKey(nextCursor = null, nextOrderKey = 2, prevCursor = null, prevOrderKey = -1))
        // On restart the mediator skips the initial refresh (cache + key present),
        // so the Room-backed timeline is served straight from cache. The end-cursor
        // is null, so paging does not fetch from the network at all.
        val api = TerminalMediaApi()
        val repo = MediaRepository(api, db, pageSize = 2)

        val ids = repo.timeline().asSnapshot().map { it.id }

        assertEquals(listOf(30L, 20L), ids)
        assertFalse("cache must serve before any network call", api.hit)
    }

    @Test
    fun selectingAProfileRefreshesTheTimelineThroughProfileQueryShowingOnlyItsMedia() = runTest {
        val api = ProfileRecordingApi()
        val repo = MediaRepository(api, db, pageSize = 50)

        // Selecting a profile refreshes through GET /api/media?profile=p1 and shows only its media.
        val filtered = repo.timeline(profile = "p1").asSnapshot().map { it.id }

        assertEquals(listOf(30L), filtered)
        assertEquals("p1", api.profiles.last())
    }

    @Test
    fun deleteCallsServerAndDropsTheCachedRow() = runTest {
        val api = RecordingMediaApi()
        val repo = MediaRepository(api, db, pageSize = 2)
        db.mediaDao().insertAll(
            listOf(
                MediaEntity(30, "image", "2024-03-03T00:00:00", orderKey = 0),
                MediaEntity(20, "image", "2024-03-02T00:00:00", orderKey = 1),
            ),
        )

        repo.delete(30)

        // The server was told to delete the item, and its cached row is gone so the
        // timeline paging source no longer serves it.
        assertEquals(listOf(30L), api.deleted)
        assertEquals(1, db.mediaDao().count(null))
    }

    @Test
    fun seekToDateStoresCursorSoNextRefreshSendsFromDate() = runTest {
        val api = RecordingMediaApi()
        val repo = MediaRepository(api, db, pageSize = 2)

        repo.seekToDate("2023-12-31")
        repo.timeline().asSnapshot()

        assertEquals("2023-12-31", api.fromDates.first())
    }

    @Test
    fun resetToLatestClearsCursorSoNextRefreshSendsNullFromDate() = runTest {
        val api = RecordingMediaApi()
        val repo = MediaRepository(api, db, pageSize = 2)

        repo.seekToDate("2023-12-31")
        repo.resetToLatest()
        repo.timeline().asSnapshot()

        assertNull(api.fromDates.first())
    }

    /** Newest-first fixture spanning 2025, two 2024 months, and 2023. */
    private fun segmentDataset() = DatasetMediaApi(
        listOf(
            item(50, "2025-01-05"),
            item(40, "2024-06-15"),
            item(30, "2024-03-10"),
            item(25, "2024-03-05"),
            item(20, "2023-12-31"),
        ),
    )

    @Test
    fun segmentTimelineYearContainsOnlyThatYearNewestFirst() = runTest {
        val repo = MediaRepository(segmentDataset(), db, pageSize = 50)

        val ids = repo.segmentTimeline(fromDate = "2024-12-31", datePrefix = "2024-")
            .asSnapshot().map { it.id }

        assertEquals(listOf(40L, 30L, 25L), ids)
    }

    @Test
    fun segmentTimelineMonthExcludesAdjacentMonths() = runTest {
        val repo = MediaRepository(segmentDataset(), db, pageSize = 50)

        val ids = repo.segmentTimeline(fromDate = "2024-03-31", datePrefix = "2024-03-")
            .asSnapshot().map { it.id }

        // No 2024-06 (adjacent month), no 2025, no 2023 — only the exact year+month.
        assertEquals(listOf(30L, 25L), ids)
    }

    @Test
    fun segmentTimelineDayContainsOnlyThatDay() = runTest {
        val repo = MediaRepository(segmentDataset(), db, pageSize = 50)

        val ids = repo.segmentTimeline(fromDate = "2024-03-10", datePrefix = "2024-03-10")
            .asSnapshot().map { it.id }

        assertEquals(listOf(30L), ids)
    }

    @Test
    fun segmentTimelineDoesNotArmSeekOrDisturbTimeline() = runTest {
        val repo = MediaRepository(segmentDataset(), db, pageSize = 2)

        repo.segmentTimeline(fromDate = "2024-03-31", datePrefix = "2024-03-").asSnapshot()

        assertFalse("segment mode is a distinct path and must not arm the seek", repo.isSeekActive().value)

        // After leaving segment mode the unfiltered newest-first timeline is intact and still
        // serves every item, in order — the segment path never touched the timeline's data source.
        val ids = repo.timeline().asSnapshot { appendScrollWhile { it.id > 0 } }.map { it.id }
        assertEquals(listOf(50L, 40L, 30L, 25L, 20L), ids)
    }

    @Test
    fun isSeekActiveEmitsTrueAfterSeekAndFalseAfterReset() = runTest {
        val api = RecordingMediaApi()
        val repo = MediaRepository(api, db, pageSize = 2)

        assertFalse(repo.isSeekActive().value)
        repo.seekToDate("2023-12-31")
        assertTrue(repo.isSeekActive().value)
        repo.resetToLatest()
        assertFalse(repo.isSeekActive().value)
    }
}

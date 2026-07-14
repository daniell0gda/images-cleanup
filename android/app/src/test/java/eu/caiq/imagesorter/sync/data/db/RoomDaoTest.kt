package eu.caiq.imagesorter.sync.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.caiq.imagesorter.sync.data.db.entity.FailureEntity
import eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomDaoTest {

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
    fun syncedCacheUpsertAndMarkVerifiedRoundTrip() = runTest {
        val dao = db.syncedCacheDao()
        dao.upsert(SyncedCacheEntity("a.jpg", "2024-01-01T00:00:00", 10, "SYNCED", syncedAt = 5))

        assertEquals(1, dao.syncedItems().size)

        dao.markVerified("a.jpg", "2024-01-01T00:00:00", 10, verifiedAt = 99)

        assertEquals(99L, dao.observeAll().first().single().lastVerifiedAt)
    }

    @Test
    fun pendingUploadReturnsNewestFirstAndUpdatesStatusOffsetAndDeletes() = runTest {
        val dao = db.pendingUploadDao()
        dao.upsert(
            listOf(
                PendingUploadEntity("old", 1, "old.jpg", "2024-01-01T00:00:00", 1, "image/jpeg", status = "PENDING", sortKey = 100),
                PendingUploadEntity("new", 2, "new.jpg", "2024-02-01T00:00:00", 1, "image/jpeg", status = "PENDING", sortKey = 200),
            ),
        )

        assertEquals(listOf("new", "old"), dao.pending().map { it.fileId }) // newest-first by sortKey

        dao.setStatus("new", "IN_PROGRESS")
        dao.setOffset("new", 512)
        val updated = dao.pending().first { it.fileId == "new" }
        assertEquals("IN_PROGRESS", updated.status)
        assertEquals(512L, updated.serverOffset)

        dao.delete("old")
        assertEquals(listOf("new"), dao.pending().map { it.fileId })
    }

    @Test
    fun syncedCacheDeleteByMediaStoreIdsRemovesMatchingRowsRegardlessOfStatus() = runTest {
        val dao = db.syncedCacheDao()
        dao.upsert(
            listOf(
                SyncedCacheEntity("synced.jpg", "2024-01-01T00:00:00", 10, "SYNCED", mediaStoreId = 100, syncedAt = 1),
                SyncedCacheEntity("notpeople.jpg", "2024-01-02T00:00:00", 20, "UNCLASSIFIED", mediaStoreId = 200, syncedAt = 2),
                SyncedCacheEntity("keep.jpg", "2024-01-03T00:00:00", 30, "SYNCED", mediaStoreId = 300, syncedAt = 3),
            ),
        )

        // Deleting from the sync grid must prune a SYNCED row too, not only UNCLASSIFIED.
        dao.deleteByMediaStoreIds(listOf(100, 200))

        assertEquals(listOf(300L), dao.observeAll().first().map { it.mediaStoreId })
    }

    @Test
    fun pendingUploadDeleteByMediaStoreIdsRemovesMatchingRows() = runTest {
        val dao = db.pendingUploadDao()
        dao.upsert(
            listOf(
                PendingUploadEntity("f1", 100, "a.jpg", "2024-01-01T00:00:00", 1, "image/jpeg", status = "PENDING", sortKey = 100),
                PendingUploadEntity("f2", 200, "b.jpg", "2024-02-01T00:00:00", 1, "image/jpeg", status = "PENDING", sortKey = 200),
            ),
        )

        dao.deleteByMediaStoreIds(listOf(100))

        assertEquals(listOf("f2"), dao.pending().map { it.fileId })
    }

    @Test
    fun failureUpsertAndClearRoundTrip() = runTest {
        val dao = db.failureDao()
        dao.upsert(FailureEntity("a.jpg", "2024-01-01T00:00:00", 10, "UNREADABLE", retryable = false, failedAt = 1))

        assertEquals(1, dao.observeAll().first().size)

        dao.clear("a.jpg", "2024-01-01T00:00:00", 10)

        assertTrue(dao.observeAll().first().isEmpty())
        assertNull(dao.retryable().firstOrNull())
    }

    @Test
    fun failureUnreportedReturnsOnlyUnreportedRowsAndMarkReportedFlipsTheFlag() = runTest {
        val dao = db.failureDao()
        dao.upsert(FailureEntity("a.jpg", "2024-01-01T00:00:00", 10, "UNREADABLE", retryable = false, failedAt = 1))
        dao.upsert(FailureEntity("b.jpg", "2024-01-02T00:00:00", 20, "NETWORK", retryable = true, failedAt = 2))

        assertEquals(setOf("a.jpg", "b.jpg"), dao.unreported().map { it.name }.toSet())

        dao.markReported("a.jpg", "2024-01-01T00:00:00", 10)

        assertEquals(listOf("b.jpg"), dao.unreported().map { it.name })
    }
}

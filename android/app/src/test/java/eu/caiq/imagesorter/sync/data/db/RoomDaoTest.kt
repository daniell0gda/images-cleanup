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
    fun failureUpsertAndClearRoundTrip() = runTest {
        val dao = db.failureDao()
        dao.upsert(FailureEntity("a.jpg", "2024-01-01T00:00:00", 10, "UNREADABLE", retryable = false, failedAt = 1))

        assertEquals(1, dao.observeAll().first().size)

        dao.clear("a.jpg", "2024-01-01T00:00:00", 10)

        assertTrue(dao.observeAll().first().isEmpty())
        assertNull(dao.retryable().firstOrNull())
    }
}

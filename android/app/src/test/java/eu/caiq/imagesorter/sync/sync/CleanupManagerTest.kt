package eu.caiq.imagesorter.sync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import eu.caiq.imagesorter.sync.domain.model.Identity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CleanupManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var manager: CleanupManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(SyncApi::class.java)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        manager = CleanupManager(api, db.syncedCacheDao(), context.contentResolver)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun verifyReturnsOnlyPresentItemsAndMarksThemVerified() = runTest {
        val dao = db.syncedCacheDao()
        dao.upsert(
            listOf(
                SyncedCacheEntity("present.jpg", "2024-01-01T00:00:00", 10, "SYNCED", syncedAt = 1),
                SyncedCacheEntity("gone.jpg", "2024-01-02T00:00:00", 20, "SYNCED", syncedAt = 1),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"name":"present.jpg","created_on":"2024-01-01T00:00:00","size":10,"present":true},
                    {"name":"gone.jpg","created_on":"2024-01-02T00:00:00","size":20,"present":false}
                ]}""",
            ),
        )

        val present = manager.verifySyncedItems()

        assertEquals(listOf(Identity("present.jpg", "2024-01-01T00:00:00", 10)), present)

        val rows = dao.observeAll().first().associateBy { it.name }
        assertNotNull(rows.getValue("present.jpg").lastVerifiedAt)
        assertNull(rows.getValue("gone.jpg").lastVerifiedAt)
    }

    @Test
    fun scopedVerifyVerifiesOnlyTheProvidedSubset() = runTest {
        val dao = db.syncedCacheDao()
        dao.upsert(
            listOf(
                SyncedCacheEntity("in-scope.jpg", "2024-01-01T00:00:00", 10, "SYNCED", syncedAt = 1),
                SyncedCacheEntity("out-of-scope.jpg", "2024-01-02T00:00:00", 20, "SYNCED", syncedAt = 1),
            ),
        )
        // The server only ever sees and confirms the scoped identity.
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"name":"in-scope.jpg","created_on":"2024-01-01T00:00:00","size":10,"present":true}
                ]}""",
            ),
        )

        val scope = listOf(Identity("in-scope.jpg", "2024-01-01T00:00:00", 10))
        val present = manager.verifySyncedItems(scope = scope)

        // Returns present-only from the scoped subset, not the full synced set.
        assertEquals(scope, present)

        // The /verify request body carried exactly the scoped subset (one identity).
        val recorded = server.takeRequest()
        assertEquals("/api/sync/verify", recorded.path)
        val sent = Moshi.Builder().build().adapter(List::class.java).fromJson(recorded.body.readUtf8())!!
        assertEquals(1, sent.size)
        val firstIdentity = sent.single() as Map<*, *>
        assertEquals("in-scope.jpg", firstIdentity["name"])

        // Only the scoped, confirmed-present row is marked verified.
        val rows = dao.observeAll().first().associateBy { it.name }
        assertNotNull(rows.getValue("in-scope.jpg").lastVerifiedAt)
        assertNull(rows.getValue("out-of-scope.jpg").lastVerifiedAt)
    }
}

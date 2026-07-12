package eu.caiq.imagesorter.sync.sync

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import eu.caiq.imagesorter.sync.data.api.ChunkUploader
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.dto.ChunkResponse
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import eu.caiq.imagesorter.sync.data.prefs.SecurePrefs
import eu.caiq.imagesorter.sync.data.prefs.SyncPrefs
import eu.caiq.imagesorter.sync.domain.model.Identity
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import eu.caiq.imagesorter.sync.support.FakeMediaSource
import eu.caiq.imagesorter.sync.support.FakeSyncPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SyncApi
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(SyncApi::class.java)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun item(name: String, size: Long) = MediaItem(
        mediaStoreId = name.hashCode().toLong(),
        uri = Uri.parse("content://media/external/file/${name.hashCode()}"),
        identity = Identity(name, "2024-01-01T00:00:00", size),
        mimeType = "image/jpeg",
    )

    /** A ChunkUploader that always succeeds by echoing the full file size. */
    private class EchoUploader : ChunkUploader {
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse = ChunkResponse(offset = offset + length, length = length)
    }

    private fun engine(
        scanner: FakeMediaSource,
        prefs: SyncPrefs,
        uploader: ChunkUploader = EchoUploader(),
        batchSize: Int = 100,
        now: () -> Long = System::currentTimeMillis,
    ) = SyncEngine(
        api = api,
        scanner = scanner,
        uploader = TusUploader(api, uploader, chunkSize = 1_000_000),
        securePrefs = prefs,
        syncedCacheDao = db.syncedCacheDao(),
        pendingUploadDao = db.pendingUploadDao(),
        failureDao = db.failureDao(),
        uploadBatchSize = batchSize,
        now = now,
    )

    /** Mutable monotonic clock for debounce tests. */
    private class FakeClock(var millis: Long = 0) : () -> Long {
        override fun invoke(): Long = millis
    }

    /** Routes by request path so concurrent uploads don't depend on enqueue order. */
    private fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
    }

    private fun resp(body: String) = MockResponse().setBody(body)

    @Test
    fun discoveryIsRestrictedToTheCameraFolder() = runTest {
        // Only camera media must be backed up — app media (Viber, screenshots, …)
        // is excluded by restricting the MediaStore enumerate to DCIM/Camera/.
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp("""{"results":[]}""")
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(emptyList())
        engine(scanner, FakeSyncPrefs()).discover()

        assertEquals(setOf("DCIM/Camera/"), scanner.lastFolders)
    }

    @Test
    fun incrementalRunEnumeratesFromThePersistedGenerationWatermark() = runTest {
        // A capture-triggered incremental run enumerates only what changed since the
        // persisted watermark, but stays restricted to the camera folder.
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp("""{"results":[]}""")
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(emptyList())
        engine(scanner, FakeSyncPrefs(mediaGenerationWatermark = 42L)).run(incremental = true)

        assertEquals(42L, scanner.lastSinceGeneration)
        assertEquals(setOf("DCIM/Camera/"), scanner.lastFolders)
    }

    @Test
    fun fullRunAndDiscoverEnumerateFromNoWatermarkDespiteAPersistedGeneration() = runTest {
        // Full run() and discover() ignore the persisted watermark and enumerate the
        // whole camera folder (NO_WATERMARK) so the mandated full reconcile is exact.
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp("""{"results":[]}""")
                else -> resp("{}")
            }
        }
        val runScanner = FakeMediaSource(emptyList())
        engine(runScanner, FakeSyncPrefs(mediaGenerationWatermark = 42L)).run()
        assertEquals(SecurePrefs.NO_WATERMARK, runScanner.lastSinceGeneration)
        assertEquals(setOf("DCIM/Camera/"), runScanner.lastFolders)

        val discoverScanner = FakeMediaSource(emptyList())
        engine(discoverScanner, FakeSyncPrefs(mediaGenerationWatermark = 42L)).discover()
        assertEquals(SecurePrefs.NO_WATERMARK, discoverScanner.lastSinceGeneration)
        assertEquals(setOf("DCIM/Camera/"), discoverScanner.lastFolders)
    }

    @Test
    fun fullRunReachingDonePersistsCompletionTimestamp() = runTest {
        // A full run that completes stamps its wall-clock completion time (the value
        // the app-open throttle later reads) using the engine's now provider.
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp("""{"results":[]}""")
                else -> resp("{}")
            }
        }
        val prefs = FakeSyncPrefs()
        val engine = engine(FakeMediaSource(emptyList()), prefs, now = FakeClock(123_456))
        engine.run()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertEquals(123_456L, prefs.getLastFullSyncAtMillis())
    }

    @Test
    fun runEndingInErrorDoesNotPersistTheCompletionTimestamp() = runTest {
        // A run that ends in ERROR (session-open 503) must NOT stamp a completion
        // time, so the throttle never treats a failed sync as a successful one.
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> MockResponse().setResponseCode(503).setBody("{}")
                else -> resp("{}")
            }
        }
        val prefs = FakeSyncPrefs()
        val engine = engine(FakeMediaSource(listOf(item("a.jpg", 5))), prefs, now = FakeClock(999))
        engine.run()

        assertEquals(SyncPhase.ERROR, engine.progress.value.phase)
        assertEquals(null, prefs.getLastFullSyncAtMillis())
    }

    @Test
    fun discoverPopulatesWorkingSetWithoutUploading() = runTest {
        // App open: discovery must surface what needs backing up (populate the
        // pending queue) but must NOT upload — no session is opened.
        val a = item("a.jpg", 5)
        val skip = item("skip.jpg", 7)
        val sessionsOpened = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"skip.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":true}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> {
                    sessionsOpened.incrementAndGet()
                    resp("""{"session_id":"sess"}""")
                }
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(a, skip)), FakeSyncPrefs())
        engine.discover()

        // The not-synced file is queued (visible as "to back up").
        val queued = db.pendingUploadDao().observeAll().first().map { it.name }.toSet()
        assertEquals(setOf("a.jpg"), queued)
        // The already-synced file was cached, not queued.
        assertTrue(db.syncedCacheDao().syncedItems().any { it.name == "skip.jpg" })
        // Nothing was uploaded.
        assertEquals("discovery must not open an upload session", 0, sessionsOpened.get())
        assertEquals(SyncPhase.IDLE, engine.progress.value.phase)
    }

    @Test
    fun discoverPrunesCacheRowsForLocallyDeletedMedia() = runTest {
        // The user deleted files from the gallery: their MediaStore ids no longer
        // enumerate, so their stale synced/pending rows must be dropped from the
        // working set instead of lingering forever.
        val present = item("present.jpg", 5)
        val gonePending = item("gone-pending.jpg", 7)
        val goneSynced = item("gone-synced.jpg", 9)
        db.pendingUploadDao().upsert(
            PendingUploadEntity(
                fileId = "f-gone",
                mediaStoreId = gonePending.mediaStoreId,
                name = gonePending.identity.name,
                createdOn = gonePending.identity.createdOn,
                size = gonePending.identity.size,
                mimeType = "image/jpeg",
                status = "PENDING",
            ),
        )
        db.syncedCacheDao().upsert(
            SyncedCacheEntity(
                name = goneSynced.identity.name,
                createdOn = goneSynced.identity.createdOn,
                size = goneSynced.identity.size,
                status = "SYNCED",
                mediaStoreId = goneSynced.mediaStoreId,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"present.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                else -> resp("{}")
            }
        }

        // Only present.jpg still enumerates from the device.
        engine(FakeMediaSource(listOf(present)), FakeSyncPrefs()).discover()

        val pendingIds = db.pendingUploadDao().allMediaStoreIds().toSet()
        val syncedIds = db.syncedCacheDao().allMediaStoreIds().toSet()
        // The deleted files' rows were pruned; the still-present file remains queued.
        assertTrue(gonePending.mediaStoreId !in pendingIds)
        assertTrue(goneSynced.mediaStoreId !in syncedIds)
        assertTrue(present.mediaStoreId in pendingIds)
    }

    @Test
    fun incrementalRunDoesNotPruneMediaMissingFromThePartialEnumerate() = runTest {
        // An incremental enumerate sees only what changed since the watermark, so it
        // must NOT be treated as the full device set — otherwise every untouched file
        // would be wrongly pruned from the cache.
        val existing = item("existing.jpg", 5)
        db.syncedCacheDao().upsert(
            SyncedCacheEntity(
                name = existing.identity.name,
                createdOn = existing.identity.createdOn,
                size = existing.identity.size,
                status = "SYNCED",
                mediaStoreId = existing.mediaStoreId,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp("""{"results":[]}""")
                else -> resp("{}")
            }
        }

        // Incremental enumerate returns nothing new; the cached row must survive.
        engine(FakeMediaSource(emptyList()), FakeSyncPrefs(mediaGenerationWatermark = 42L))
            .run(incremental = true)

        assertTrue(existing.mediaStoreId in db.syncedCacheDao().allMediaStoreIds().toSet())
    }

    @Test
    fun uploadsOnlyNotAlreadySyncedAndCachesAlreadySynced() = runTest {
        val keep = item("keep.jpg", 5)
        val skip = item("skip.jpg", 7)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"keep.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"skip.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":true}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                // No outcome reported yet, so keep.jpg stays queued; this test
                // pins reconcile's synced-vs-to-upload separation, not reporting.
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(keep, skip)), FakeSyncPrefs()).run()

        // skip.jpg was cached as synced without ever being queued for upload.
        val synced = db.syncedCacheDao().syncedItems().map { it.name }.toSet()
        assertTrue(synced.contains("skip.jpg"))
        // keep.jpg was the only thing queued.
        val queuedNames = db.pendingUploadDao().observeAll().first().map { it.name }.toSet()
        assertEquals(setOf("keep.jpg"), queuedNames)
    }

    @Test
    fun successfulRunPersistsSyncedClearsPendingAndRecordsFailures() = runTest {
        val ok = item("ok.jpg", 5)
        val bad = item("bad.jpg", 6)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"ok.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"bad.jpg","created_on":"2024-01-01T00:00:00","size":6,"already_synced":false}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":6,"length":6}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(outcomesForQueued())
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(ok, bad)), FakeSyncPrefs())
        engine.run()

        val synced = db.syncedCacheDao().syncedItems().map { it.name }.toSet()
        assertTrue(synced.contains("ok.jpg"))

        val failures = db.failureDao().observeAll().first().map { it.name }.toSet()
        assertTrue(failures.contains("bad.jpg"))

        // ok.jpg cleared from pending; bad.jpg was terminal (no_video_destination) so also dropped.
        assertTrue(db.pendingUploadDao().pending().none { it.name == "ok.jpg" })
    }

    @Test
    fun completedSessionReportingOutcomesAdvancesSyncCompletions() = runTest {
        // The timeline-refresh trigger: once a batch finishes server-side grouping
        // (session completed + outcomes reported), the engine must bump
        // syncCompletions so timeline observers refresh and the newly synced items
        // appear. Drives the real completeAndReport path, not a stand-in flow.
        val ok = item("ok.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"ok.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(syncedOutcomeForQueued("ok.jpg"))
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(ok)), FakeSyncPrefs(concurrency = 1))
        val before = engine.syncCompletions.value
        engine.run()

        assertEquals(
            "a completed session that reported outcomes advances syncCompletions",
            before + 1,
            engine.syncCompletions.value,
        )
    }

    @Test
    fun retryableFailureStaysPendingTerminalFailureDropsFromQueue() = runTest {
        val retry = item("retry.jpg", 5)
        val terminal = item("term.jpg", 6)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"retry.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"term.jpg","created_on":"2024-01-01T00:00:00","size":6,"already_synced":false}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":6,"length":6}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(outcomesRetryVsTerminal())
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(retry, terminal)), FakeSyncPrefs()).run()

        val pendingNames = db.pendingUploadDao().pending()
            .filter { it.status == "PENDING" }
            .map { it.name }
            .toSet()
        assertTrue("retryable left PENDING", pendingNames.contains("retry.jpg"))
        assertTrue("terminal dropped", db.pendingUploadDao().pending().none { it.name == "term.jpg" })
    }

    @Test
    fun unauthorizedClearsTokenAndEndsInRepairError() = runTest {
        val one = item("one.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"one.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                else -> resp("{}")
            }
        }
        val prefs = FakeSyncPrefs()
        val engine = engine(FakeMediaSource(listOf(one)), prefs, uploader = UnauthorizedUploader())

        engine.run()

        assertTrue(prefs.repairCleared)
        assertEquals(SyncPhase.ERROR, engine.progress.value.phase)
    }

    @Test
    fun sessionOpen503SurfacesDistinctNotConfiguredStateAndKeepsProfile() = runTest {
        // The server has no `sync:` template, so session-open returns 503. This must
        // become a distinct "sync isn't set up" state — not a generic "Server error
        // 503" — and must NOT clear the chosen profile (the picker step is untouched).
        val a = item("a.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> MockResponse().setResponseCode(503).setBody("{}")
                else -> resp("{}")
            }
        }
        val prefs = FakeSyncPrefs()
        val engine = engine(FakeMediaSource(listOf(a)), prefs)

        engine.run()

        assertEquals(SyncPhase.ERROR, engine.progress.value.phase)
        assertEquals(SyncEngine.SYNC_NOT_CONFIGURED_MESSAGE, engine.progress.value.message)
        assertTrue("503 must not be the generic server-error message", engine.progress.value.message != "Server error 503")
        assertTrue("503 must not clear the chosen profile", !prefs.profileCleared)
    }

    @Test
    fun sessionOpen404ClearsProfileAndSignalsProfileRemoved() = runTest {
        // The chosen profile was deleted server-side: session-open returns 404. The
        // engine self-heals by clearing the stored profileId and surfacing the
        // distinct "profile removed" signal the UI routes back to the picker on.
        val a = item("a.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> MockResponse().setResponseCode(404).setBody("{}")
                else -> resp("{}")
            }
        }
        val prefs = FakeSyncPrefs()
        val engine = engine(FakeMediaSource(listOf(a)), prefs)

        engine.run()

        assertEquals(SyncPhase.ERROR, engine.progress.value.phase)
        assertEquals(SyncEngine.PROFILE_REMOVED_MESSAGE, engine.progress.value.message)
        assertTrue("404 must clear the removed profile", prefs.profileCleared)
    }

    @Test
    fun transportErrorLeavesFilePendingAndContinuesBatch() = runTest {
        val a = item("a.jpg", 5)
        val b = item("b.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"b.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }
        // First file throws IOException, the rest upload normally.
        val engine = engine(FakeMediaSource(listOf(a, b)), FakeSyncPrefs(concurrency = 1), uploader = FailFirstUploader())

        engine.run()

        // The run completed (DONE), did not abort, and the failed file is left PENDING/resumable.
        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        val pending = db.pendingUploadDao().pending()
        assertTrue(pending.any { it.status == "PENDING" })
    }

    @Test
    fun reRunningDiscoveryDoesNotDuplicatePendingRowsForSameFile() = runTest {
        // Reproduces the double-tap crash: a second run used to mint a fresh fileId
        // for the same media file and insert a duplicate pending row (same
        // mediaStoreId), which then collided on the UI grid key.
        val a = item("a.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(listOf(a))
        val prefs = FakeSyncPrefs(concurrency = 1)
        // Uploads always fail (IOException) so the file stays PENDING across runs.
        engine(scanner, prefs, uploader = AlwaysFailUploader()).run()
        engine(scanner, prefs, uploader = AlwaysFailUploader()).run()

        val pending = db.pendingUploadDao().observeAll().first()
        assertEquals(
            "exactly one pending row per media file after two runs",
            1,
            pending.count { it.mediaStoreId == a.mediaStoreId },
        )
    }

    @Test
    fun uploadsEachBatchInItsOwnSessionSoPlacementIsIncremental() = runTest {
        // With batchSize 1, three files become three independent sessions, each
        // completed (and placed server-side) before the next — so "photos safe"
        // grows incrementally instead of only after the whole library uploads.
        val items = listOf(item("a.jpg", 5), item("b.jpg", 6), item("c.jpg", 7))
        val sessionsOpened = java.util.concurrent.atomic.AtomicInteger(0)
        val completes = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"b.jpg","created_on":"2024-01-01T00:00:00","size":6,"already_synced":false},
                        {"name":"c.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":false}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> {
                    sessionsOpened.incrementAndGet()
                    resp("""{"session_id":"sess"}""")
                }
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":7,"length":7}""")
                req.path!!.endsWith("/complete") -> {
                    completes.incrementAndGet()
                    resp("""{"status":"complete"}""")
                }
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(items), FakeSyncPrefs(concurrency = 1), batchSize = 1)
        engine.run()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertEquals("one session per batch of 1", 3, sessionsOpened.get())
        assertEquals("one complete per batch", 3, completes.get())
    }

    @Test
    fun resumesInterruptedSessionInsteadOfReuploadingFromScratch() = runTest {
        // reconcile says the file is not synced yet, but its bytes are already in
        // an open session from an interrupted run. The engine must resume into
        // that session — no new session opened, uploading the same file id from
        // the offset the server reports — so already-uploaded bytes are kept.
        val resume = item("resume.jpg", 10)
        val newSessions = java.util.concurrent.atomic.AtomicInteger(0)
        var completedSessionId: String? = null
        dispatch { req ->
            val path = req.path!!
            when {
                path.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"resume.jpg","created_on":"2024-01-01T00:00:00","size":10,
                        "already_synced":false,"uploaded_offset":6,
                        "resume_session_id":"oldsess","resume_file_id":"oldfid"}]}""",
                )
                path.endsWith("/sessions") -> {
                    newSessions.incrementAndGet()
                    resp("""{"session_id":"newsess"}""")
                }
                path.contains("/files/") -> resp("""{"offset":6}""")
                path.endsWith("/complete") -> {
                    completedSessionId = path.substringAfter("/sessions/").substringBefore("/complete")
                    resp("""{"status":"complete"}""")
                }
                path.endsWith("/outcomes") -> resp(
                    """{"outcomes":[{"file_id":"oldfid","name":"resume.jpg","status":"synced"}]}""",
                )
                else -> resp("{}")
            }
        }

        val uploader = RecordingUploader()
        engine(FakeMediaSource(listOf(resume)), FakeSyncPrefs(concurrency = 1), uploader = uploader).run()

        assertEquals("no new session opened for a resumable file", 0, newSessions.get())
        assertEquals("uploaded into the existing session", listOf("oldsess"), uploader.sessions)
        assertEquals("reused the server's file id", listOf("oldfid"), uploader.fileIds)
        assertEquals("resumed from the server-held offset", listOf(6L), uploader.offsets)
        assertEquals("completed the existing session", "oldsess", completedSessionId)
        // The file ended up placed and cached as synced.
        assertTrue(db.syncedCacheDao().syncedItems().any { it.name == "resume.jpg" })
    }

    /** Records the session/file/offset each chunk upload was driven with. */
    private class RecordingUploader : ChunkUploader {
        val sessions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fileIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val offsets = java.util.Collections.synchronizedList(mutableListOf<Long>())
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            sessions.add(sessionId)
            fileIds.add(fileId)
            offsets.add(offset)
            return ChunkResponse(offset = offset + length, length = length)
        }
    }

    @Test
    fun zeroByteFileCompletesInOnePassAndIsNeverResumed() = runTest {
        // [wontfix-rationale] A zero-length file is never "resumed": there are no
        // bytes to resume, so its uploaded_offset stays 0 and the upload loop sends
        // no chunk. This documents that the missing resume path is harmless — a
        // zero-byte file completes in a single pass (offset 0 == size 0).
        val empty = item("empty.jpg", 0)
        val zero = ZeroByteUploader()
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"empty.jpg","created_on":"2024-01-01T00:00:00","size":0,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(empty)), FakeSyncPrefs(concurrency = 1), uploader = zero)
        engine.run()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertEquals("a zero-byte file sends no chunk (nothing to upload or resume)", 0, zero.chunks)
    }

    /** Asserts no chunk is ever requested; a zero-byte file must skip the loop. */
    private class ZeroByteUploader : ChunkUploader {
        @Volatile var chunks = 0
            private set
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            chunks += 1
            return ChunkResponse(offset = offset + length, length = length)
        }
    }

    @Test
    fun uploadAllNeverExceedsConfiguredConcurrency() = runTest {
        val concurrency = 3
        val fileCount = 8
        val items = (0 until fileCount).map { item("c$it.jpg", 5) }

        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    val results = items.joinToString(",") {
                        """{"name":"${it.identity.name}","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}"""
                    }
                    resp("""{"results":[$results]}""")
                }
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        val uploader = GatingUploader(releaseWhenInFlight = concurrency)
        val engine = engine(FakeMediaSource(items), FakeSyncPrefs(concurrency = concurrency), uploader = uploader)
        engine.run()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertTrue(
            "peak in-flight ${uploader.maxInFlight} must be <= concurrency $concurrency",
            uploader.maxInFlight <= concurrency,
        )
        assertEquals("all files uploaded", fileCount, uploader.entered)
    }

    /**
     * Holds each upload open until [releaseWhenInFlight] uploads have entered
     * concurrently, making overlap real, then releases all so the run completes.
     * Records the peak simultaneous entries.
     */
    private class GatingUploader(private val releaseWhenInFlight: Int) : ChunkUploader {
        private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        @Volatile var maxInFlight = 0
            private set
        private val enteredCount = java.util.concurrent.atomic.AtomicInteger(0)
        val entered: Int get() = enteredCount.get()

        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            enteredCount.incrementAndGet()
            val now = inFlight.incrementAndGet()
            synchronized(this) { if (now > maxInFlight) maxInFlight = now }
            // Once the permitted number are concurrently in-flight, open the gate.
            if (now >= releaseWhenInFlight) gate.complete(Unit)
            try {
                gate.await()
                return ChunkResponse(offset = offset + length, length = length)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class UnauthorizedUploader : ChunkUploader {
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            val body = "".toResponseBody(null)
            throw retrofit2.HttpException(retrofit2.Response.error<Any>(401, body))
        }
    }

    private class AlwaysFailUploader : ChunkUploader {
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse = throw java.io.IOException("boom")
    }

    private class FailFirstUploader : ChunkUploader {
        private var first = true
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            if (first) {
                first = false
                throw java.io.IOException("boom")
            }
            return ChunkResponse(offset = offset + length, length = length)
        }
    }

    // Build outcomes referencing the actual queued file ids (read synchronously).
    private fun outcomesForQueued(): String = runBlocking {
        val rows = db.pendingUploadDao().pending().associateBy { it.name }
        val ok = rows.getValue("ok.jpg").fileId
        val bad = rows.getValue("bad.jpg").fileId
        """{"outcomes":[
            {"file_id":"$ok","name":"ok.jpg","status":"synced"},
            {"file_id":"$bad","name":"bad.jpg","status":"failed","reason":"no_video_destination","retryable":false}
        ]}"""
    }

    @Test
    fun terminalFailedFileIsAbsentFromCleanupCandidateSet() = runTest {
        val ok = item("ok.jpg", 5)
        val bad = item("bad.jpg", 6)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"ok.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"bad.jpg","created_on":"2024-01-01T00:00:00","size":6,"already_synced":false}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":6,"length":6}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(outcomesForQueued())
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(ok, bad)), FakeSyncPrefs()).run()

        // bad.jpg failed with a TERMINAL reason (no_video_destination); it must
        // never appear in the cleanup verify candidate set so the delete pipeline
        // can never act on a file that was not actually backed up.
        val candidateNames = db.syncedCacheDao().syncedItems().map { it.name }.toSet()
        assertTrue("synced file is a candidate", candidateNames.contains("ok.jpg"))
        assertTrue("terminal-failed file must NOT be a cleanup candidate", !candidateNames.contains("bad.jpg"))
    }

    @Test
    fun reconcileAlreadySyncedRemovesStuckPendingRowForThatIdentity() = runTest {
        // After a `complete` timeout left a file as IN_PROGRESS in the pending
        // queue while the server actually synced it, the next reconcile reports
        // that identity already_synced. The stuck pending/IN_PROGRESS row must be
        // removed so no ghost "in progress" tile remains alongside the synced one.
        val stuck = item("stuck.jpg", 9)
        db.pendingUploadDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.PendingUploadEntity(
                fileId = "stale-fid",
                mediaStoreId = stuck.mediaStoreId,
                name = stuck.identity.name,
                createdOn = stuck.identity.createdOn,
                size = stuck.identity.size,
                mimeType = stuck.mimeType,
                sessionId = "oldsess",
                serverOffset = 3,
                status = "IN_PROGRESS",
                sortKey = 0,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"stuck.jpg","created_on":"2024-01-01T00:00:00","size":9,"already_synced":true}]}""",
                )
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(stuck)), FakeSyncPrefs()).discover()

        assertTrue(
            "stuck pending row removed when reconcile says already_synced",
            db.pendingUploadDao().pending().none { it.mediaStoreId == stuck.mediaStoreId },
        )
        assertTrue(
            "identity cached as synced",
            db.syncedCacheDao().syncedItems().any { it.name == "stuck.jpg" },
        )
    }

    @Test
    fun outcomeIsRoutedByEchoedIdentityWhenFileIdDoesNotMatch() = runTest {
        // The server echoes a synced outcome whose identity round-trips identically
        // to a pending row, but whose file_id differs from the row the engine
        // persisted (e.g. server-side id reassignment). The outcome must NOT be
        // silently dropped — the matching identity routes it to synced handling.
        val one = item("one.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"one.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(
                    // Identity round-trips identically, but file_id is a stranger.
                    """{"outcomes":[{"file_id":"server-reassigned-id","name":"one.jpg","status":"synced"}]}""",
                )
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(one)), FakeSyncPrefs(concurrency = 1)).run()

        assertTrue(
            "outcome routed to synced via echoed identity (not dropped)",
            db.syncedCacheDao().syncedItems().any { it.name == "one.jpg" },
        )
        assertTrue(
            "pending row cleared once routed",
            db.pendingUploadDao().pending().none { it.name == "one.jpg" },
        )
    }

    @Test
    fun reportRoutesEveryOutcomeFromOnePrecomputedPendingMap() = runTest {
        // Reporting must resolve each outcome against a single precomputed snapshot
        // of the pending rows (no per-outcome rescan): with several files in one
        // session, every outcome is routed to the right place — synced rows are
        // cached and cleared, failed rows are recorded — in a single report pass.
        val items = (0 until 5).map { item("m$it.jpg", it.toLong() + 1) }
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    val results = items.joinToString(",") {
                        """{"name":"${it.identity.name}","created_on":"2024-01-01T00:00:00","size":${it.identity.size},"already_synced":false}"""
                    }
                    resp("""{"results":[$results]}""")
                }
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":999,"length":1}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(outcomesMixedForQueued())
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(items), FakeSyncPrefs(concurrency = 1)).run()

        // Odd-indexed files were reported synced; even-indexed reported failed.
        val synced = db.syncedCacheDao().syncedItems()
            .filter { it.status == "SYNCED" }
            .map { it.name }
            .toSet()
        assertEquals(setOf("m1.jpg", "m3.jpg"), synced)
        val failures = db.failureDao().observeAll().first().map { it.name }.toSet()
        assertEquals(setOf("m0.jpg", "m2.jpg", "m4.jpg"), failures)
        // Synced rows cleared from pending; the terminal failures dropped too.
        assertTrue(db.pendingUploadDao().pending().none { it.name == "m1.jpg" })
        assertTrue(db.pendingUploadDao().pending().none { it.name == "m3.jpg" })
    }

    @Test
    fun overrideUploadOpensForcePlaceSessionAndSyncsOverriddenItem() = runTest {
        // The user picks a "not people" item and taps "Sync anyway". The engine
        // must open a session with force_place=true, upload the selected item, and
        // on a "synced" outcome flip its cache row from UNCLASSIFIED to SYNCED so it
        // leaves the Not People set.
        val np = item("np.jpg", 5)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        var openBody: String? = null
        dispatch { req ->
            when {
                req.path!!.endsWith("/sessions") -> {
                    openBody = req.body.readUtf8()
                    resp("""{"session_id":"sess"}""")
                }
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(syncedOutcomeForQueued("np.jpg"))
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(np)), FakeSyncPrefs(concurrency = 1))
        engine.overrideUpload(listOf(np))

        assertTrue("session opened with force_place=true", openBody?.contains("\"force_place\":true") == true)
        val row = db.syncedCacheDao().observeAll().first().firstOrNull { it.name == "np.jpg" }
        assertEquals("overridden item becomes SYNCED", "SYNCED", row?.status)
        assertTrue("pending cleared after sync", db.pendingUploadDao().pending().none { it.name == "np.jpg" })
    }

    private fun syncedOutcomeForQueued(name: String): String = runBlocking {
        val fid = db.pendingUploadDao().pending().first { it.name == name }.fileId
        """{"outcomes":[{"file_id":"$fid","name":"$name","status":"synced"}]}"""
    }

    @Test
    fun overrideUploadClearsNotPeopleAndQueuesImmediatelyEvenWhileAPassIsActive() = runBlocking {
        // "Sync anyway" tapped while a sync pass is active must still drop the items
        // from the Not People set right away: their UNCLASSIFIED cache row is cleared
        // and they enter the pending queue before any override upload begins.
        val work = item("work.jpg", 5)
        val np = item("np.jpg", 6)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"work.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"worksess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }
        val gate = GateUploader()
        val engine = engine(FakeMediaSource(listOf(work)), FakeSyncPrefs(concurrency = 1), uploader = gate)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val first = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        gate.awaitEntered() // run() holds the guard, blocked mid-upload

        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(np)) }

        assertTrue(
            "UNCLASSIFIED cache row cleared immediately, before the pass ends",
            db.syncedCacheDao().observeAll().first().none { it.name == "np.jpg" && it.status == "UNCLASSIFIED" },
        )
        assertTrue(
            "queued for upload immediately",
            db.pendingUploadDao().pending().any { it.name == "np.jpg" },
        )

        gate.release()
        first.join()
    }

    @Test
    fun overrideMidPassPreemptsLiveSessionThenForcePlacesThenResumes() = runBlocking {
        // A "Sync Now" override landing while the live session is uploading must:
        //  - leave files not yet started (w2, w3) PENDING, out of the live session,
        //  - let the in-flight file (w1) finish and be reported by the partial session,
        //  - open the force_place session only after the live session is completed
        //    (never two sessions open at once),
        //  - force-place the tapped item (np) ahead of the resumed pending files,
        //  - resume the bailed files in a fresh session within the same run, ending DONE
        //    with every file synced exactly once.
        val w1 = item("w1.jpg", 5)
        val w2 = item("w2.jpg", 5)
        val w3 = item("w3.jpg", 5)
        val np = item("np.jpg", 6)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        val open = java.util.concurrent.atomic.AtomicInteger(0)
        val maxOpen = java.util.concurrent.atomic.AtomicInteger(0)
        val sessionCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val uploader = GateAndRecordUploader()
        dispatch { req ->
            val path = req.path!!
            when {
                path.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"w1.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"w2.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"w3.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}
                    ]}""",
                )
                path.endsWith("/sessions") -> {
                    val n = open.incrementAndGet()
                    maxOpen.updateAndGet { kotlin.math.max(it, n) }
                    resp("""{"session_id":"sess${sessionCounter.incrementAndGet()}"}""")
                }
                path.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                path.endsWith("/files") -> resp("""{"offset":6,"length":6}""")
                path.endsWith("/complete") -> {
                    open.decrementAndGet()
                    resp("""{"status":"complete"}""")
                }
                path.endsWith("/outcomes") -> {
                    val sid = path.substringAfter("/sessions/").substringBefore("/outcomes")
                    val parts = uploader.namesFor(sid)
                        .joinToString(",") { """{"file_id":"srv-$it","name":"$it","status":"synced"}""" }
                    resp("""{"outcomes":[$parts]}""")
                }
                else -> resp("{}")
            }
        }
        val engine = engine(FakeMediaSource(listOf(w1, w2, w3)), FakeSyncPrefs(concurrency = 1), uploader = uploader)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val run = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        uploader.awaitEntered() // w1 is in flight, holding the only permit

        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(np)) }
        uploader.release()
        run.join()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        val synced = db.syncedCacheDao().syncedItems().map { it.name }.toSet()
        assertTrue("all files ended synced", synced.containsAll(setOf("w1.jpg", "w2.jpg", "w3.jpg", "np.jpg")))
        assertTrue("pending drained", db.pendingUploadDao().pending().isEmpty())

        val uploadsPerName = uploader.allNames().groupingBy { it }.eachCount()
        assertEquals("w1 uploaded exactly once", 1, uploadsPerName["w1.jpg"])
        assertEquals("w2 uploaded exactly once", 1, uploadsPerName["w2.jpg"])
        assertEquals("w3 uploaded exactly once", 1, uploadsPerName["w3.jpg"])
        assertEquals("np uploaded exactly once", 1, uploadsPerName["np.jpg"])

        // Criterion 1 & 2: the preempted live session uploaded only the single
        // in-flight file; the two not-yet-started work files bailed and stayed PENDING.
        val sessions = uploader.sessions()
        val liveSession = sessions.first()
        assertEquals("only the in-flight file uploaded into the preempted live session", 1, liveSession.size)
        val bailedWork = setOf("w1.jpg", "w2.jpg", "w3.jpg") - liveSession.toSet()
        assertEquals("the other two work files bailed out of the live session", 2, bailedWork.size)

        // Criterion 3: only one upload session is ever open at a time.
        assertEquals("never two sessions open concurrently", 1, maxOpen.get())

        // Criterion 4 & 6: np's force_place session ran ahead of the resumed pending files.
        bailedWork.forEach { resumed ->
            assertTrue(
                "the override jumped ahead of resumed pending file $resumed",
                uploader.sessionOrderOf("np.jpg") < uploader.sessionOrderOf(resumed),
            )
        }
    }

    /** Gates the first upload to enter and records, per session, the names uploaded. */
    private class GateAndRecordUploader : ChunkUploader {
        private val bySession = LinkedHashMap<String, MutableList<String>>()
        private val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val gated = java.util.concurrent.atomic.AtomicBoolean(false)
        suspend fun awaitEntered() = entered.await()
        fun release() = release.complete(Unit)
        fun namesFor(sessionId: String): List<String> =
            synchronized(bySession) { bySession[sessionId]?.toList() ?: emptyList() }
        fun allNames(): List<String> = synchronized(bySession) { bySession.values.flatten() }
        fun sessions(): List<List<String>> = synchronized(bySession) { bySession.values.map { it.toList() } }
        fun sessionOrderOf(name: String): Int =
            synchronized(bySession) { bySession.values.indexOfFirst { it.contains(name) } }
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            synchronized(bySession) { bySession.getOrPut(sessionId) { mutableListOf() }.add(item.identity.name) }
            // Hold only the very first file that enters, so with concurrency 1 exactly
            // one file is in flight when the override lands — independent of the order
            // the semaphore hands out its single permit.
            if (gated.compareAndSet(false, true)) {
                entered.complete(Unit)
                release.await()
            }
            return ChunkResponse(offset = offset + length, length = length)
        }
    }

    @Test
    fun overrideForItemAlreadyUploadingInLiveSessionIsNotPlacedTwice() = runBlocking {
        // The user taps "Sync Now" on the very file the live session is already
        // uploading. The override must not upload or place that physical file a
        // second time: it uploads exactly once and no force_place session re-sends it.
        val w = item("w.jpg", 5)
        val forceSessions = java.util.concurrent.atomic.AtomicInteger(0)
        val uploader = GateAndRecordUploader()
        dispatch { req ->
            val path = req.path!!
            when {
                path.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"w.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                path.endsWith("/sessions") -> {
                    if (req.body.readUtf8().contains("\"force_place\":true")) {
                        forceSessions.incrementAndGet()
                        resp("""{"session_id":"force${forceSessions.get()}"}""")
                    } else {
                        resp("""{"session_id":"live"}""")
                    }
                }
                path.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                path.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                path.endsWith("/complete") -> resp("""{"status":"complete"}""")
                path.endsWith("/outcomes") -> {
                    val sid = path.substringAfter("/sessions/").substringBefore("/outcomes")
                    val parts = uploader.namesFor(sid)
                        .joinToString(",") { """{"file_id":"srv-$it","name":"$it","status":"synced"}""" }
                    resp("""{"outcomes":[$parts]}""")
                }
                else -> resp("{}")
            }
        }
        val engine = engine(FakeMediaSource(listOf(w)), FakeSyncPrefs(concurrency = 1), uploader = uploader)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val run = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        uploader.awaitEntered() // w is in flight in the live session

        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(w)) }
        uploader.release()
        run.join()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertEquals(
            "the same physical file is uploaded exactly once across the live and force_place sessions",
            1,
            uploader.allNames().count { it == "w.jpg" },
        )
        assertTrue("w ended synced", db.syncedCacheDao().syncedItems().any { it.name == "w.jpg" })
        assertTrue("pending drained", db.pendingUploadDao().pending().none { it.name == "w.jpg" })
    }

    @Test
    fun progressStaysCoherentAcrossThePreemptOverrideResumeSplit() = runBlocking {
        // Through the whole preempt → override → resume split the progress flow must
        // stay coherent: completedFiles never exceeds totalFiles, and the run's final
        // phase is DONE (no corruption from carrying one pass's counters into another).
        val w1 = item("w1.jpg", 5)
        val w2 = item("w2.jpg", 5)
        val w3 = item("w3.jpg", 5)
        val np = item("np.jpg", 6)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        val uploader = GateAndRecordUploader()
        dispatch { req ->
            val path = req.path!!
            when {
                path.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"w1.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"w2.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"w3.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}
                    ]}""",
                )
                path.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                path.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                path.endsWith("/files") -> resp("""{"offset":6,"length":6}""")
                path.endsWith("/complete") -> resp("""{"status":"complete"}""")
                path.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }
        val engine = engine(FakeMediaSource(listOf(w1, w2, w3)), FakeSyncPrefs(concurrency = 1), uploader = uploader)

        val violations = java.util.Collections.synchronizedList(mutableListOf<String>())
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val collector = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            engine.progress.collect { p ->
                if (p.completedFiles > p.totalFiles) {
                    violations.add("completed=${p.completedFiles} > total=${p.totalFiles}")
                }
            }
        }

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val run = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        uploader.awaitEntered()
        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(np)) }
        uploader.release()
        run.join()
        collector.cancel()

        assertTrue("completedFiles never exceeded totalFiles: $violations", violations.isEmpty())
        assertEquals("the run ends in DONE", SyncPhase.DONE, engine.progress.value.phase)
        assertTrue(
            "final completedFiles within totalFiles",
            engine.progress.value.completedFiles <= engine.progress.value.totalFiles,
        )
    }

    @Test
    fun overrideUploadIsDeferredNotDroppedWhenAPassIsActiveThenForceUploaded() = runBlocking {
        // "Sync Now" during an active pass is never dropped and never opens a second
        // concurrent session. Under the preempt-and-jump-ahead semantics: no force_place
        // session opens while the live session is still uploading; once the in-flight
        // file finishes and the live session is completed, the override runs in its own
        // force_place session AHEAD of the still-PENDING work file, which then resumes.
        val workInFlight = item("work.jpg", 5)
        val workPending = item("later.jpg", 5)
        val np = item("np.jpg", 6)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        val open = java.util.concurrent.atomic.AtomicInteger(0)
        val maxOpen = java.util.concurrent.atomic.AtomicInteger(0)
        val forceSessions = java.util.concurrent.atomic.AtomicInteger(0)
        val sessionCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val uploader = GateAndRecordUploader()
        dispatch { req ->
            val path = req.path!!
            when {
                path.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"work.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"later.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}
                    ]}""",
                )
                path.endsWith("/sessions") -> {
                    val n = open.incrementAndGet()
                    maxOpen.updateAndGet { kotlin.math.max(it, n) }
                    if (req.body.readUtf8().contains("\"force_place\":true")) forceSessions.incrementAndGet()
                    resp("""{"session_id":"sess${sessionCounter.incrementAndGet()}"}""")
                }
                path.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                path.endsWith("/files") -> resp("""{"offset":6,"length":6}""")
                path.endsWith("/complete") -> {
                    open.decrementAndGet()
                    resp("""{"status":"complete"}""")
                }
                path.endsWith("/outcomes") -> {
                    val sid = path.substringAfter("/sessions/").substringBefore("/outcomes")
                    val parts = uploader.namesFor(sid)
                        .joinToString(",") { """{"file_id":"srv-$it","name":"$it","status":"synced"}""" }
                    resp("""{"outcomes":[$parts]}""")
                }
                else -> resp("{}")
            }
        }
        val engine = engine(FakeMediaSource(listOf(workInFlight, workPending)), FakeSyncPrefs(concurrency = 1), uploader = uploader)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val first = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        uploader.awaitEntered() // the live session is mid-upload, holding the only permit

        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(np)) }
        assertEquals("no force_place session opens while the live session is mid-upload", 0, forceSessions.get())

        uploader.release()
        first.join()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertEquals("force_place session opened once the live session was completed", 1, forceSessions.get())
        assertEquals("never two sessions open concurrently", 1, maxOpen.get())
        // Never dropped: the override ends SYNCED.
        val row = db.syncedCacheDao().observeAll().first().firstOrNull { it.name == "np.jpg" }
        assertEquals("overridden item becomes SYNCED and leaves the Not People set", "SYNCED", row?.status)
        // Whichever work file was not the in-flight one bailed and resumed after np.
        val bailedWork = setOf("work.jpg", "later.jpg") - uploader.sessions().first().toSet()
        assertEquals("one work file bailed out of the preempted live session", 1, bailedWork.size)
        bailedWork.forEach { name ->
            assertTrue(
                "the override jumped ahead of the still-pending work file $name",
                uploader.sessionOrderOf("np.jpg") < uploader.sessionOrderOf(name),
            )
        }
        assertTrue(
            "the preempted work file still finished",
            db.syncedCacheDao().syncedItems().map { it.name }.containsAll(bailedWork),
        )
    }

    @Test
    fun severalOverridesRequestedWhileAPassIsActiveAreAllDeferredThenForceUploaded() = runBlocking {
        // Several "Sync Now" taps during one active pass: each is force-uploaded exactly
        // once (none dropped) and jumps ahead of the still-PENDING work file, all without
        // ever opening a second concurrent session.
        val workInFlight = item("work.jpg", 7)
        val workPending = item("later.jpg", 7)
        val a = item("a.jpg", 6)
        val b = item("b.jpg", 7)
        listOf(a, b).forEach { np ->
            db.syncedCacheDao().upsert(
                eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                    name = np.identity.name,
                    createdOn = np.identity.createdOn,
                    size = np.identity.size,
                    status = "UNCLASSIFIED",
                    mediaStoreId = np.mediaStoreId,
                    mimeType = np.mimeType,
                ),
            )
        }
        val open = java.util.concurrent.atomic.AtomicInteger(0)
        val maxOpen = java.util.concurrent.atomic.AtomicInteger(0)
        val forceSessions = java.util.concurrent.atomic.AtomicInteger(0)
        val sessionCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val uploader = GateAndRecordUploader()
        dispatch { req ->
            val path = req.path!!
            when {
                path.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"work.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":false},
                        {"name":"later.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":false}
                    ]}""",
                )
                path.endsWith("/sessions") -> {
                    val n = open.incrementAndGet()
                    maxOpen.updateAndGet { kotlin.math.max(it, n) }
                    if (req.body.readUtf8().contains("\"force_place\":true")) forceSessions.incrementAndGet()
                    resp("""{"session_id":"sess${sessionCounter.incrementAndGet()}"}""")
                }
                path.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                path.endsWith("/files") -> resp("""{"offset":7,"length":7}""")
                path.endsWith("/complete") -> {
                    open.decrementAndGet()
                    resp("""{"status":"complete"}""")
                }
                path.endsWith("/outcomes") -> {
                    val sid = path.substringAfter("/sessions/").substringBefore("/outcomes")
                    val parts = uploader.namesFor(sid)
                        .joinToString(",") { """{"file_id":"srv-$it","name":"$it","status":"synced"}""" }
                    resp("""{"outcomes":[$parts]}""")
                }
                else -> resp("{}")
            }
        }
        val engine = engine(FakeMediaSource(listOf(workInFlight, workPending)), FakeSyncPrefs(concurrency = 1), uploader = uploader)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val first = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        uploader.awaitEntered() // the live session is mid-upload, holding the only permit

        // Two separate "Sync Now" requests land while the pass is active.
        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(a)) }
        kotlinx.coroutines.withTimeout(5_000) { engine.overrideUpload(listOf(b)) }
        assertEquals("no force_place session opens while the live session is mid-upload", 0, forceSessions.get())

        uploader.release()
        first.join()

        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        assertEquals("never two sessions open concurrently", 1, maxOpen.get())
        // None dropped, none duplicated: each override uploaded exactly once and SYNCED.
        val uploadsPerName = uploader.allNames().groupingBy { it }.eachCount()
        assertEquals("a force-uploaded exactly once", 1, uploadsPerName["a.jpg"])
        assertEquals("b force-uploaded exactly once", 1, uploadsPerName["b.jpg"])
        val synced = db.syncedCacheDao().observeAll().first()
            .filter { it.status == "SYNCED" }
            .map { it.name }
            .toSet()
        assertTrue("both overrides ended up SYNCED", synced.containsAll(setOf("a.jpg", "b.jpg")))
        // Both jumped ahead of whichever work file bailed out of the live session.
        val bailedWork = setOf("work.jpg", "later.jpg") - uploader.sessions().first().toSet()
        assertEquals("one work file bailed out of the preempted live session", 1, bailedWork.size)
        bailedWork.forEach { name ->
            assertTrue(
                "override a jumped ahead of the still-pending work file $name",
                uploader.sessionOrderOf("a.jpg") < uploader.sessionOrderOf(name),
            )
            assertTrue(
                "override b jumped ahead of the still-pending work file $name",
                uploader.sessionOrderOf("b.jpg") < uploader.sessionOrderOf(name),
            )
        }
    }

    @Test
    fun overrideUploadOnFailedItemClearsFailureRecordAndSyncs() = runTest {
        // "Sync Now" on a FAILED row: the force_place synced outcome must clear the
        // item's failure record (via the existing completeAndReport flow) so it leaves
        // the working set and the failed count drops.
        val failed = item("failed.jpg", 8)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = failed.identity.name,
                createdOn = failed.identity.createdOn,
                size = failed.identity.size,
                status = "FAILED",
                mediaStoreId = failed.mediaStoreId,
                mimeType = failed.mimeType,
            ),
        )
        db.failureDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.FailureEntity(
                name = failed.identity.name,
                createdOn = failed.identity.createdOn,
                size = failed.identity.size,
                reason = "UNKNOWN",
                retryable = true,
                message = null,
                failedAt = 1L,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":8,"length":8}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(syncedOutcomeForQueued("failed.jpg"))
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(failed)), FakeSyncPrefs(concurrency = 1)).overrideUpload(listOf(failed))

        assertTrue(
            "failure record cleared on the synced outcome",
            db.failureDao().observeAll().first().none { it.name == "failed.jpg" },
        )
        val row = db.syncedCacheDao().observeAll().first().firstOrNull { it.name == "failed.jpg" }
        assertEquals("item becomes SYNCED and leaves the working set", "SYNCED", row?.status)
        assertTrue("pending cleared after sync", db.pendingUploadDao().pending().none { it.name == "failed.jpg" })
    }

    @Test
    fun discoverSkipsLocallyUnclassifiedItemsEvenWhenServerSaysNotSynced() = runTest {
        // The server discards "not people" bytes, so it reports already_synced=false
        // for them forever. An item the local cache already marks UNCLASSIFIED must
        // NOT be re-queued for upload — it stays out of the not-synced/upload set
        // and the pending queue. A genuinely not-synced item is still queued.
        val np = item("np.jpg", 5)
        val fresh = item("fresh.jpg", 7)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"np.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false},
                        {"name":"fresh.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":false}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(np, fresh)), FakeSyncPrefs()).discover()

        val queued = db.pendingUploadDao().observeAll().first().map { it.name }.toSet()
        assertTrue("genuinely not-synced item still queued", queued.contains("fresh.jpg"))
        assertTrue("locally UNCLASSIFIED item not re-queued", !queued.contains("np.jpg"))
    }

    @Test
    fun reconcileLeavesUnclassifiedRowPresentInCache() = runTest {
        // An UNCLASSIFIED item must remain in synced_cache after reconcile (it is
        // not deleted) so the Not People set stays visible in the UI.
        val np = item("np.jpg", 5)
        db.syncedCacheDao().upsert(
            eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity(
                name = np.identity.name,
                createdOn = np.identity.createdOn,
                size = np.identity.size,
                status = "UNCLASSIFIED",
                mediaStoreId = np.mediaStoreId,
                mimeType = np.mimeType,
            ),
        )
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"np.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(listOf(np)), FakeSyncPrefs()).discover()

        val row = db.syncedCacheDao().observeAll().first().firstOrNull { it.name == "np.jpg" }
        assertEquals("UNCLASSIFIED row survives reconcile", "UNCLASSIFIED", row?.status)
    }

    @Test
    fun unclassifiedOutcomeCachesUnclassifiedClearsPendingAndDoesNotCountAsFailed() = runTest {
        // The server discards a "not people" image (status "unclassified"). The
        // engine must cache it as UNCLASSIFIED (so it appears in the Not People
        // set), drop its pending/failure rows, and NOT count it as a failure.
        val np = item("np.jpg", 5)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"np.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp(unclassifiedOutcomeForQueued())
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(np)), FakeSyncPrefs(concurrency = 1))
        engine.run()

        val row = db.syncedCacheDao().observeAll().first().firstOrNull { it.name == "np.jpg" }
        assertEquals("cached as UNCLASSIFIED", "UNCLASSIFIED", row?.status)
        assertTrue("pending row cleared", db.pendingUploadDao().pending().none { it.name == "np.jpg" })
        assertTrue("no failure recorded", db.failureDao().observeAll().first().none { it.name == "np.jpg" })
        assertEquals("unclassified is not a failure", 0, engine.progress.value.failedFiles)
    }

    private fun unclassifiedOutcomeForQueued(): String = runBlocking {
        val fid = db.pendingUploadDao().pending().first { it.name == "np.jpg" }.fileId
        """{"outcomes":[{"file_id":"$fid","name":"np.jpg","status":"unclassified"}]}"""
    }

    // Mixed outcomes: odd-indexed synced, even-indexed terminal failure.
    private fun outcomesMixedForQueued(): String = runBlocking {
        val rows = db.pendingUploadDao().pending().associateBy { it.name }
        val parts = (0 until 5).map { i ->
            val fid = rows.getValue("m$i.jpg").fileId
            if (i % 2 == 1) {
                """{"file_id":"$fid","name":"m$i.jpg","status":"synced"}"""
            } else {
                """{"file_id":"$fid","name":"m$i.jpg","status":"failed","reason":"no_video_destination","retryable":false}"""
            }
        }
        """{"outcomes":[${parts.joinToString(",")}]}"""
    }

    @Test
    fun secondRunWhileFirstIsActiveIsANoOpAndOpensNoNewSession() = runBlocking {
        // A second run() invoked while the first run() is still in-flight (uploading)
        // must be a no-op: the atomic re-entrancy guard means it opens no new session
        // and starts no second upload pass.
        val one = item("one.jpg", 5)
        val sessionsOpened = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"one.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> {
                    sessionsOpened.incrementAndGet()
                    resp("""{"session_id":"sess"}""")
                }
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        val gate = GateUploader()
        val engine = engine(FakeMediaSource(listOf(one)), FakeSyncPrefs(concurrency = 1), uploader = gate)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val first = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        gate.awaitEntered()

        // First run is now blocked inside its upload critical section. A second run
        // while it is active must return promptly (no-op) — the atomic guard makes
        // it bail before opening a session. Without the guard it would either open a
        // second session or block on the same gate; the timeout catches the block.
        kotlinx.coroutines.withTimeout(5_000) { engine.run() }
        assertEquals("second run must open no new session", 1, sessionsOpened.get())

        gate.release()
        first.join()
        // After the guard releases, the engine is reusable.
        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
    }

    @Test
    fun incrementalRunWhileAFullPassIsActiveIsANoOpAndOpensNoNewSession() = runBlocking {
        // Capture triggers share the single engine path: an incremental run() that
        // lands while a full pass holds the atomic guard is a no-op — no second
        // engine, no new session, no second upload pass.
        val one = item("one.jpg", 5)
        val sessionsOpened = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[{"name":"one.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":false}]}""",
                )
                req.path!!.endsWith("/sessions") -> {
                    sessionsOpened.incrementAndGet()
                    resp("""{"session_id":"sess"}""")
                }
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":5,"length":5}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        val gate = GateUploader()
        val engine = engine(FakeMediaSource(listOf(one)), FakeSyncPrefs(), uploader = gate)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val first = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        gate.awaitEntered()

        kotlinx.coroutines.withTimeout(5_000) { engine.run(incremental = true) }
        assertEquals("incremental run must open no new session while a pass is active", 1, sessionsOpened.get())

        gate.release()
        first.join()
        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
    }

    /** Suspends one upload until released, signalling when it has entered. */
    private class GateUploader : ChunkUploader {
        private val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        suspend fun awaitEntered() = entered.await()
        fun release() = release.complete(Unit)
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            entered.complete(Unit)
            release.await()
            return ChunkResponse(offset = offset + length, length = length)
        }
    }

    @Test
    fun discoverConcurrentWithActiveRunIsANoOpViaAtomicGuard() = runBlocking {
        // discover() must be gated by the SAME atomic guard as run(), not by a
        // check-then-act read of the progress phase. run() acquires the guard
        // before it touches the phase; a discover() that lands in that window must
        // still bail (issue no /reconcile), which a phase-read guard would miss.
        val one = item("one.jpg", 5)
        val reconciles = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    reconciles.incrementAndGet()
                    resp("""{"results":[{"name":"one.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":true}]}""")
                }
                else -> resp("{}")
            }
        }

        // Prefs that blocks the first getProfileId() — run() holds the guard here
        // but has not yet set any busy phase, exposing the check-then-act gap.
        val gatedPrefs = GatedProfilePrefs()
        val engine = engine(FakeMediaSource(listOf(one)), gatedPrefs)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val first = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { engine.run() }
        gatedPrefs.awaitEntered()

        // run() owns the guard but phase is still the initial (non-busy) value.
        engine.discover()
        assertEquals("discover during an active run must issue no reconcile", 0, reconciles.get())

        gatedPrefs.release()
        first.join()
    }

    /** SyncPrefs whose first getProfileId() blocks until released, signalling entry. */
    private class GatedProfilePrefs : SyncPrefs {
        private val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val gate = java.util.concurrent.CountDownLatch(1)
        fun release() = gate.countDown()
        suspend fun awaitEntered() = entered.await()
        override fun getProfileId(): String? {
            entered.complete(Unit)
            gate.await()
            return "groupby"
        }
        override fun clearProfileId() {}
        override fun getUploadConcurrency(): Int = 1
        override fun getMediaGeneration(): Long = eu.caiq.imagesorter.sync.data.prefs.SecurePrefs.NO_WATERMARK
        override fun setMediaGeneration(value: Long) {}
        override fun getLastFullSyncAtMillis(): Long? = null
        override fun setLastFullSyncAtMillis(value: Long) {}
        override fun clearTokenForRepair() {}
        override fun getServerAddress(): String? = null
        override fun setServerAddress(value: String?) {}
    }

    @Test
    fun secondDiscoverImmediatelyAfterFirstSkipsRedundantEnumerateAndReconcile() = runTest {
        // A discover() that fires right after a just-completed discover (e.g. the
        // user re-opening the app, or two triggers landing together) must skip the
        // redundant full enumerate + reconcile within the dedup window.
        val a = item("a.jpg", 5)
        val reconciles = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    reconciles.incrementAndGet()
                    resp("""{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":true}]}""")
                }
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(listOf(a))
        val clock = FakeClock(1_000)
        val engine = engine(scanner, FakeSyncPrefs(), now = clock)

        engine.discover()
        engine.discover()

        assertEquals("second back-to-back discover must reuse the prior pass", 1, reconciles.get())
    }

    @Test
    fun discoverAfterDedupWindowPerformsAnotherFullPass() = runTest {
        // Once the dedup window has elapsed, a discover() must do a full pass again
        // so stale state is refreshed.
        val a = item("a.jpg", 5)
        val reconciles = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    reconciles.incrementAndGet()
                    resp("""{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":true}]}""")
                }
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(listOf(a))
        val clock = FakeClock(1_000)
        val engine = engine(scanner, FakeSyncPrefs(), now = clock)

        engine.discover()
        clock.millis += 10 * 60 * 1000 // advance well past the dedup window
        engine.discover()

        assertEquals("a discover after the window does a full pass again", 2, reconciles.get())
    }

    @Test
    fun discoverAfterBackwardClockJumpStillPerformsFullPass() = runTest {
        // A backward clock jump between two discover() calls (NTP correction or the
        // user changing device time) must not make now()-lastFullReconcileAt
        // negative and thereby suppress a discover that should run.
        val a = item("a.jpg", 5)
        val reconciles = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    reconciles.incrementAndGet()
                    resp("""{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":true}]}""")
                }
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(listOf(a))
        val clock = FakeClock(1_000_000)
        val engine = engine(scanner, FakeSyncPrefs(), now = clock)

        engine.discover()
        clock.millis -= 500_000 // wall-clock jumps backward → negative delta
        engine.discover()

        assertEquals("a backward clock jump must not suppress the second discover", 2, reconciles.get())
    }

    @Test
    fun runAlwaysReconcilesEvenRightAfterADiscover() = runTest {
        // run() must never be debounced by a recent discover — a "Back up now" tap
        // immediately after app-open discovery must still reconcile and upload.
        val a = item("a.jpg", 5)
        val reconciles = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    reconciles.incrementAndGet()
                    resp("""{"results":[{"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":true}]}""")
                }
                else -> resp("{}")
            }
        }
        val scanner = FakeMediaSource(listOf(a))
        val clock = FakeClock(1_000)
        val engine = engine(scanner, FakeSyncPrefs(), now = clock)

        engine.discover()
        engine.run()

        assertEquals("run reconciles regardless of a recent discover", 2, reconciles.get())
    }

    @Test
    fun allAlreadySyncedIsNoOpAndOpensNoSession() = runTest {
        val a = item("a.jpg", 5)
        val b = item("b.jpg", 7)
        val sessionsOpened = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> resp(
                    """{"results":[
                        {"name":"a.jpg","created_on":"2024-01-01T00:00:00","size":5,"already_synced":true},
                        {"name":"b.jpg","created_on":"2024-01-01T00:00:00","size":7,"already_synced":true}
                    ]}""",
                )
                req.path!!.endsWith("/sessions") -> {
                    sessionsOpened.incrementAndGet()
                    resp("""{"session_id":"sess"}""")
                }
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(listOf(a, b)), FakeSyncPrefs())
        engine.run()

        assertEquals(0, sessionsOpened.get())
        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
        // Both identities were cached as synced without any upload session.
        val synced = db.syncedCacheDao().syncedItems().map { it.name }.toSet()
        assertEquals(setOf("a.jpg", "b.jpg"), synced)
    }

    @Test
    fun emptyDiscoveredSetIsNoOpAndOpensNoSession() = runTest {
        val sessionsOpened = java.util.concurrent.atomic.AtomicInteger(0)
        dispatch { req ->
            when {
                req.path!!.endsWith("/sessions") -> {
                    sessionsOpened.incrementAndGet()
                    resp("""{"session_id":"sess"}""")
                }
                req.path!!.endsWith("/reconcile") -> resp("""{"results":[]}""")
                else -> resp("{}")
            }
        }

        val engine = engine(FakeMediaSource(emptyList()), FakeSyncPrefs())
        engine.run()

        assertEquals(0, sessionsOpened.get())
        assertEquals(SyncPhase.DONE, engine.progress.value.phase)
    }

    @Test
    fun reconcilesAcrossMultipleBatchesAndMergesAlreadySyncedDecisions() = runTest {
        // More than RECONCILE_BATCH (500) items to force at least 2 reconcile calls.
        val total = 750
        val items = (0 until total).map { item("f$it.jpg", it.toLong() + 1) }
        // batch1Skip is in the FIRST batch and is NOT synced (must be queued).
        // batch2Skip is in the SECOND batch and IS synced (must be cached, not queued).
        val batch1Upload = items[10].identity.name
        val batch2Synced = items[600].identity.name

        val reconcileCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val moshi = Moshi.Builder().build()
        val listAdapter = moshi.adapter(List::class.java)

        dispatch { req ->
            when {
                req.path!!.endsWith("/reconcile") -> {
                    reconcileCalls.incrementAndGet()
                    val posted = listAdapter.fromJson(req.body.readUtf8())!!
                    val results = posted.joinToString(",") { raw ->
                        val m = raw as Map<*, *>
                        val name = m["name"] as String
                        // Only batch2Synced is already synced; everything else not synced.
                        val synced = name == batch2Synced
                        """{"name":"$name","created_on":"${m["created_on"]}","size":${(m["size"] as Double).toLong()},"already_synced":$synced}"""
                    }
                    resp("""{"results":[$results]}""")
                }
                req.path!!.endsWith("/sessions") -> resp("""{"session_id":"sess"}""")
                req.path!!.contains("/files/") -> MockResponse().setResponseCode(404).setBody("{}")
                req.path!!.endsWith("/files") -> resp("""{"offset":999999,"length":1}""")
                req.path!!.endsWith("/complete") -> resp("""{"status":"complete"}""")
                req.path!!.endsWith("/outcomes") -> resp("""{"outcomes":[]}""")
                else -> resp("{}")
            }
        }

        engine(FakeMediaSource(items), FakeSyncPrefs()).run()

        assertTrue("expected >1 reconcile request", reconcileCalls.get() > 1)

        val syncedNames = db.syncedCacheDao().syncedItems().map { it.name }.toSet()
        assertTrue("batch-2 already-synced item cached", syncedNames.contains(batch2Synced))

        val queuedNames = db.pendingUploadDao().observeAll().first().map { it.name }.toSet()
        assertTrue("batch-1 not-synced item queued", queuedNames.contains(batch1Upload))
        assertTrue("batch-2 synced item not queued", !queuedNames.contains(batch2Synced))
    }

    private fun outcomesRetryVsTerminal(): String = runBlocking {
        val rows = db.pendingUploadDao().pending().associateBy { it.name }
        val retry = rows.getValue("retry.jpg").fileId
        val term = rows.getValue("term.jpg").fileId
        """{"outcomes":[
            {"file_id":"$retry","name":"retry.jpg","status":"failed","reason":"size_mismatch","retryable":true},
            {"file_id":"$term","name":"term.jpg","status":"failed","reason":"unreadable","retryable":false}
        ]}"""
    }
}

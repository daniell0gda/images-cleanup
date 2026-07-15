package imagesorter.sync.sync

import android.net.Uri
import com.squareup.moshi.Moshi
import imagesorter.sync.data.api.ChunkUploader
import imagesorter.sync.data.api.SyncApi
import imagesorter.sync.data.api.dto.ChunkResponse
import imagesorter.sync.domain.model.Identity
import imagesorter.sync.domain.model.MediaItem
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TusUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SyncApi

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
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun item(size: Long) = MediaItem(
        mediaStoreId = 1,
        uri = Uri.parse("content://media/external/file/1"),
        identity = Identity(name = "a.jpg", createdOn = "2024-01-01T00:00:00", size = size),
        mimeType = "image/jpeg",
    )

    /** Records the byte offsets each chunk started at; echoes server offset = offset + length. */
    private class RecordingUploader : ChunkUploader {
        val startOffsets = mutableListOf<Long>()
        override suspend fun uploadChunk(
            sessionId: String,
            fileId: String,
            item: MediaItem,
            offset: Long,
            length: Long,
        ): ChunkResponse {
            startOffsets += offset
            return ChunkResponse(offset = offset + length, length = length)
        }
    }

    @Test
    fun resumesFromServerOffsetAndUploadsOnlyRemainingChunks() = runTest {
        server.enqueue(MockResponse().setBody("""{"offset":20}"""))
        val uploader = RecordingUploader()
        val tus = TusUploader(api, uploader, chunkSize = 10)

        val progress = mutableListOf<Long>()
        val finalOffset = tus.upload("sess", "file", item(size = 30)) { progress += it }

        assertEquals(listOf(20L), uploader.startOffsets) // only the missing 20..30
        assertEquals(30L, finalOffset)
        assertEquals(listOf(30L), progress)
    }

    @Test
    fun sendsNoChunksWhenServerOffsetEqualsFileSize() = runTest {
        server.enqueue(MockResponse().setBody("""{"offset":30}"""))
        val uploader = RecordingUploader()
        val tus = TusUploader(api, uploader, chunkSize = 10)

        val progress = mutableListOf<Long>()
        val finalOffset = tus.upload("sess", "file", item(size = 30)) { progress += it }

        assertEquals(emptyList<Long>(), uploader.startOffsets) // nothing uploaded
        assertEquals(30L, finalOffset) // file size unchanged
        assertEquals(emptyList<Long>(), progress)
    }

    @Test
    fun sendsNoChunksWhenServerOffsetBeyondFileSize() = runTest {
        // Stale/complete offset reported past the declared size is idempotent.
        server.enqueue(MockResponse().setBody("""{"offset":50}"""))
        val uploader = RecordingUploader()
        val tus = TusUploader(api, uploader, chunkSize = 10)

        val progress = mutableListOf<Long>()
        tus.upload("sess", "file", item(size = 30)) { progress += it }

        assertEquals(emptyList<Long>(), uploader.startOffsets)
        assertEquals(emptyList<Long>(), progress)
    }

    @Test
    fun treats404OffsetAsFreshUploadStartingAtZero() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val uploader = RecordingUploader()
        val tus = TusUploader(api, uploader, chunkSize = 10)

        val progress = mutableListOf<Long>()
        val finalOffset = tus.upload("sess", "file", item(size = 25)) { progress += it }

        // chunks start at 0, 10, 20 -> server echoes 10, 20, 25
        assertEquals(listOf(0L, 10L, 20L), uploader.startOffsets)
        assertEquals(25L, finalOffset)
        assertEquals(listOf(10L, 20L, 25L), progress)
    }
}

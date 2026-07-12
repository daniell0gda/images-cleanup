package eu.caiq.imagesorter.sync.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import eu.caiq.imagesorter.sync.data.media.ChunkSizePolicy
import eu.caiq.imagesorter.sync.data.media.ChunkedDataSource
import eu.caiq.imagesorter.sync.data.media.MediaUrls
import eu.caiq.imagesorter.sync.data.media.buildVideoMediaItem
import eu.caiq.imagesorter.sync.data.media.pauseTolerantLoadControl
import eu.caiq.imagesorter.sync.data.media.videoRequestHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ExoPlayer wiring is asserted at the request-building level (URL + bearer
 * header), not via real playback — playback is device/codec behavior (§9 Notes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoSourceBuilderTest {

    private val urls = MediaUrls("http://host:7000")

    @Test
    fun mediaItemPointsAtTheStreamEndpoint() {
        val item: MediaItem = buildVideoMediaItem(urls, id = 9)
        assertEquals("http://host:7000/api/media/9/stream", item.localConfiguration?.uri.toString())
    }

    @Test
    fun requestHeadersCarryTheBearerToken() {
        assertEquals(mapOf("Authorization" to "Bearer tok-1"), videoRequestHeaders("tok-1"))
    }

    @Test
    fun requestHeadersEmptyWithoutToken() {
        assertEquals(emptyMap<String, String>(), videoRequestHeaders(null))
    }

    @Test
    fun firstChunkRequestIsBoundedToTheInitialChunkSize() {
        val upstream = RecordingDataSource()
        val chunked = ChunkedDataSource(
            upstream = upstream,
            policy = ChunkSizePolicy(initialChunkBytes = 1000, clock = { 0L }),
            requestHeaders = emptyMap(),
        )
        // Open-ended whole-file request, as ExoPlayer issues for progressive media.
        chunked.open(openEndedSpec(urls.stream(9)))

        val firstChunk = upstream.openedSpecs.single()
        assertEquals(1000L, firstChunk.length)
        assertNotEquals(C.LENGTH_UNSET.toLong(), firstChunk.length)
    }

    @Test
    fun chunksStayAtInitialSizeWithinTheFirstTwoSeconds() {
        var now = 0L
        val upstream = RecordingDataSource()
        val chunked = ChunkedDataSource(
            upstream = upstream,
            policy = ChunkSizePolicy(initialChunkBytes = 1000, clock = { now }),
            requestHeaders = emptyMap(),
        )
        chunked.open(openEndedSpec(urls.stream(9)))
        now = 1_999L
        drainOneChunk(chunked)

        assertEquals(listOf(1000L, 1000L), upstream.openedSpecs.map { it.length })
    }

    @Test
    fun chunksGrow40PercentAfterTwoSeconds() {
        var now = 0L
        val upstream = RecordingDataSource()
        val chunked = ChunkedDataSource(
            upstream = upstream,
            policy = ChunkSizePolicy(initialChunkBytes = 1000, clock = { now }),
            requestHeaders = emptyMap(),
        )
        chunked.open(openEndedSpec(urls.stream(9)))
        now = 2_000L
        drainOneChunk(chunked)

        assertEquals(listOf(1000L, 1400L), upstream.openedSpecs.map { it.length })
    }

    @Test
    fun chunkSizeThresholdIsDrivenByTheInjectedClock() {
        var now = 0L
        val policy = ChunkSizePolicy(initialChunkBytes = 1000, clock = { now })
        policy.markOpened()

        assertEquals(1000L, policy.nextChunkBytes())
        now = 1_999L
        assertEquals(1000L, policy.nextChunkBytes())
        now = 2_000L
        assertEquals(1400L, policy.nextChunkBytes())
    }

    @Test
    fun everyChunkTargetsTheStreamEndpointCarryingTheBearerHeader() {
        var now = 0L
        val upstream = RecordingDataSource()
        val chunked = ChunkedDataSource(
            upstream = upstream,
            policy = ChunkSizePolicy(initialChunkBytes = 1000, clock = { now }),
            requestHeaders = videoRequestHeaders("tok-1"),
        )
        chunked.open(openEndedSpec(urls.stream(9)))
        now = 2_000L
        drainOneChunk(chunked)

        assertEquals(2, upstream.openedSpecs.size)
        upstream.openedSpecs.forEach { spec ->
            assertEquals("http://host:7000/api/media/9/stream", spec.uri.toString())
            assertEquals("Bearer tok-1", spec.httpRequestHeaders["Authorization"])
        }
    }

    @Test
    fun loadControlKeepsFillingBufferAndIgnoresPlayPauseState() {
        val loadControl = pauseTolerantLoadControl()
        loadControl.onPrepared(PlayerId.UNSET)

        val paused = loadParams(playWhenReady = false, bufferedDurationUs = 0)
        val playing = loadParams(playWhenReady = true, bufferedDurationUs = 0)

        // Buffer keeps filling even while paused, and the decision does not depend on play/pause.
        assertTrue(loadControl.shouldContinueLoading(paused))
        assertEquals(
            loadControl.shouldContinueLoading(playing),
            loadControl.shouldContinueLoading(paused),
        )
    }
}

private fun loadParams(playWhenReady: Boolean, bufferedDurationUs: Long): LoadControl.Parameters =
    LoadControl.Parameters(
        PlayerId.UNSET,
        Timeline.EMPTY,
        LoadControl.EMPTY_MEDIA_PERIOD_ID,
        /* playbackPositionUs = */ 0L,
        bufferedDurationUs,
        /* playbackSpeed = */ 1f,
        playWhenReady,
        /* rebuffering = */ false,
        /* targetLiveOffsetUs = */ C.TIME_UNSET,
    )

/** Reads the current chunk to its end, then reads once more so the next chunk is opened. */
private fun drainOneChunk(source: DataSource) {
    val buffer = ByteArray(4096)
    source.read(buffer, 0, buffer.size) // drains the current chunk
    source.read(buffer, 0, buffer.size) // opens the following chunk
}

/** A fake upstream that records every [DataSpec] it is opened with and serves exactly its length. */
private class RecordingDataSource : DataSource {
    val openedSpecs = mutableListOf<DataSpec>()
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        openedSpecs.add(dataSpec)
        remaining = dataSpec.length
        return dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val n = minOf(readLength.toLong(), remaining).toInt()
        remaining -= n
        return n
    }

    override fun getUri(): Uri? = openedSpecs.lastOrNull()?.uri

    override fun close() = Unit
}

private fun openEndedSpec(url: String): DataSpec =
    DataSpec.Builder()
        .setUri(Uri.parse(url))
        .setPosition(0)
        .setLength(C.LENGTH_UNSET.toLong())
        .build()

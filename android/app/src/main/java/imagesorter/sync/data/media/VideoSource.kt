package imagesorter.sync.data.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Authed media-item + HTTP `DataSource.Factory` builders for ExoPlayer video
 * streaming. Kept free of an [androidx.media3.exoplayer.ExoPlayer] instance so
 * the URL + bearer-header wiring is unit-testable without real playback.
 */

/** A Media3 [MediaItem] pointing at `/api/media/{id}/stream`. */
fun buildVideoMediaItem(urls: MediaUrls, id: Long): MediaItem =
    MediaItem.fromUri(urls.stream(id))

/** The request-header map (bearer token) for a video stream, empty when untrusted. */
fun videoRequestHeaders(token: String?): Map<String, String> = MediaUrls.authHeaders(token)

/**
 * An HTTP [DataSource.Factory] that sends the bearer [token] on every stream
 * request, so ExoPlayer's progressive reads (including Range requests) carry it.
 */
fun bearerDataSourceFactory(token: String?): DataSource.Factory =
    DefaultHttpDataSource.Factory().apply {
        val headers = videoRequestHeaders(token)
        if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
    }

/**
 * Decides how many bytes each video stream chunk should request. Reads are kept
 * to a bounded, controllable size (rather than one open-ended request) and the
 * size grows after an initial warm-up so a paused/settled stream fills faster.
 *
 * Time is read through an injectable [clock] (elapsed millis) captured at
 * [markOpened], so the before/after sizes are unit-testable without real playback.
 */
class ChunkSizePolicy(
    val initialChunkBytes: Long = DEFAULT_INITIAL_CHUNK_BYTES,
    private val growthThresholdMs: Long = GROWTH_THRESHOLD_MS,
    private val clock: () -> Long,
) {
    private var openedAtMs: Long? = null

    /** Marks the instant the stream was opened; the growth clock runs from here. */
    fun markOpened() {
        openedAtMs = clock()
    }

    /**
     * The byte length for the next chunk request: the initial size until
     * [growthThresholdMs] have elapsed since [markOpened], then 40% larger.
     */
    fun nextChunkBytes(): Long {
        val opened = openedAtMs ?: clock().also { openedAtMs = it }
        val elapsed = clock() - opened
        return if (elapsed < growthThresholdMs) {
            initialChunkBytes
        } else {
            initialChunkBytes + initialChunkBytes * GROWTH_PERMILLE / 1000L
        }
    }

    companion object {
        const val DEFAULT_INITIAL_CHUNK_BYTES = 256L * 1024
        const val GROWTH_THRESHOLD_MS = 2_000L

        /** 400/1000 = 40% growth once the threshold is crossed. */
        private const val GROWTH_PERMILLE = 400L
    }
}

/**
 * A [DataSource] that reads its upstream in bounded chunks sized by a
 * [ChunkSizePolicy], re-opening a fresh range request for each chunk. Splitting
 * one open-ended read into fixed-length range reads keeps the bytes loaded per
 * request controllable, and re-opening per chunk means [requestHeaders] (the
 * bearer token) rides on every request. The target endpoint is never rewritten.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val policy: ChunkSizePolicy,
    private val requestHeaders: Map<String, String>,
) : DataSource {

    private var original: DataSpec? = null
    private var position: Long = 0
    private var overallRemaining: Long = C.LENGTH_UNSET.toLong()
    private var chunkRemaining: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        policy.markOpened()
        original = dataSpec
        position = dataSpec.position
        overallRemaining = dataSpec.length
        openChunk()
        return dataSpec.length
    }

    private fun openChunk() {
        val chunk = policy.nextChunkBytes()
        val length =
            if (overallRemaining == C.LENGTH_UNSET.toLong()) chunk else minOf(chunk, overallRemaining)
        val chunkSpec = original!!.buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
            .withAdditionalHeaders(requestHeaders)
        upstream.open(chunkSpec)
        chunkRemaining = length
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (overallRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            upstream.close()
            openChunk()
        }
        val toRead = minOf(readLength.toLong(), chunkRemaining).toInt()
        val read = upstream.read(buffer, offset, toRead)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        position += read
        chunkRemaining -= read
        if (overallRemaining != C.LENGTH_UNSET.toLong()) overallRemaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri ?: original?.uri

    override fun close() {
        upstream.close()
    }
}

/**
 * A [LoadControl] that starts playback after buffering ~0.5s (instead of the 2.5s
 * default) so the first frame appears sooner on a fast local network. Its
 * continue-loading decision is driven purely by buffer level, never by the
 * play/pause state, so the buffer keeps filling while the user has paused.
 */
@UnstableApi
fun pauseTolerantLoadControl(): LoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 1000,
        )
        .build()

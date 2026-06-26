package eu.caiq.imagesorter.sync.data.api

import android.content.ContentResolver
import android.net.Uri
import eu.caiq.imagesorter.sync.data.api.dto.ChunkResponse
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams a single chunk of a [MediaItem] to the raw-body chunk endpoint.
 *
 * The chunk is read directly from the MediaStore content URI starting at a byte
 * offset, so nothing is buffered fully in memory — large videos stream through
 * an [okio] source. The metadata headers are filled from the item's
 * authoritative [eu.caiq.imagesorter.sync.domain.model.Identity].
 */
class UploadClient(
    private val api: SyncApi,
    private val contentResolver: ContentResolver,
) {
    /**
     * Upload bytes `[offset, offset + length)` of [item] within [sessionId].
     *
     * @param fileId stable per-file id (the phone mints this and reuses it across
     *   resume attempts so the server can match the resume offset).
     */
    suspend fun uploadChunk(
        sessionId: String,
        fileId: String,
        item: MediaItem,
        offset: Long,
        length: Long,
    ): ChunkResponse {
        val body = chunkBody(item.uri, offset, length)
        val identity = item.identity
        return api.uploadChunk(
            sessionId = sessionId,
            fileId = fileId,
            fileName = identity.name,
            fileCreatedOn = identity.createdOn,
            fileSize = identity.size,
            fileMimeType = item.mimeType,
            uploadOffset = offset,
            chunk = body,
        )
    }

    /**
     * A streaming [RequestBody] that emits exactly [length] bytes of [uri]
     * starting at [offset]. The content stream is skipped to [offset] then copied
     * in bounded reads.
     */
    private fun chunkBody(uri: Uri, offset: Long, length: Long): RequestBody =
        object : RequestBody() {
            override fun contentType() = OCTET_STREAM

            override fun contentLength(): Long = length

            override fun writeTo(sink: BufferedSink) {
                val input = contentResolver.openInputStream(uri)
                    ?: error("Unable to open input stream for $uri")
                input.use { stream ->
                    skipFully(stream, offset)
                    val source = stream.source()
                    var remaining = length
                    while (remaining > 0) {
                        val read = source.read(sink.buffer, minOf(remaining, SEGMENT))
                        if (read == -1L) break
                        remaining -= read
                        sink.flush()
                    }
                }
            }
        }

    /** InputStream.skip is allowed to skip fewer bytes; loop until [count] done. */
    private fun skipFully(stream: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // Fall back to reading when skip stalls (some providers return 0).
                if (stream.read() == -1) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val SEGMENT = 64L * 1024L
    }
}

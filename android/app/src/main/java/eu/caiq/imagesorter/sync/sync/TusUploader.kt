package eu.caiq.imagesorter.sync.sync

import eu.caiq.imagesorter.sync.data.api.ChunkUploader
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import retrofit2.HttpException

/**
 * Resumable, tus-style upload of a single file.
 *
 * Resume strategy: ask the server for the current offset (`GET .../files/{id}`),
 * then `POST` chunks starting from that offset until the whole file is on the
 * server. The server echoes the new offset after each chunk; that drives the
 * loop and is persisted by the caller so a restart resumes from the right place.
 */
class TusUploader(
    private val api: SyncApi,
    private val uploadClient: ChunkUploader,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) {
    /**
     * Upload [item] fully within [sessionId] under the stable [fileId].
     *
     * @param onProgress invoked after each accepted chunk with the new absolute
     *   server offset, so the caller can persist the resume point and update UI.
     * @return the final server offset (== file size on success).
     */
    suspend fun upload(
        sessionId: String,
        fileId: String,
        item: MediaItem,
        onProgress: suspend (offset: Long) -> Unit,
    ): Long {
        val total = item.identity.size
        var offset = resumeOffset(sessionId, fileId)

        while (offset < total) {
            val length = minOf(chunkSize, total - offset)
            val response = uploadClient.uploadChunk(
                sessionId = sessionId,
                fileId = fileId,
                item = item,
                offset = offset,
                length = length,
            )
            // Trust the server's reported offset (it is authoritative for resume).
            offset = response.offset
            onProgress(offset)
        }
        return offset
    }

    /**
     * The byte count the server already holds. A 404 here means the server has no
     * record of this file yet (fresh upload) → start at 0.
     */
    private suspend fun resumeOffset(sessionId: String, fileId: String): Long =
        try {
            api.fileOffset(sessionId, fileId).offset
        } catch (e: HttpException) {
            if (e.code() == HTTP_NOT_FOUND) 0L else throw e
        }

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val DEFAULT_CHUNK_SIZE = 8L * 1024L * 1024L // 8 MiB
    }
}

package imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Body for `POST /api/sync/sessions`. */
@JsonClass(generateAdapter = true)
data class OpenSessionRequest(
    @Json(name = "profile_id") val profileId: String,
    @Json(name = "force_place") val forcePlace: Boolean = false,
)

/** Response from `POST /api/sync/sessions`. */
@JsonClass(generateAdapter = true)
data class OpenSessionResponse(
    @Json(name = "session_id") val sessionId: String,
)

/**
 * Response from `GET /api/sync/sessions/{sid}/files/{fid}` — the resume offset,
 * i.e. how many bytes the server already holds for this file.
 */
@JsonClass(generateAdapter = true)
data class FileOffsetResponse(
    @Json(name = "offset") val offset: Long,
)

/**
 * Response from a chunk `POST /api/sync/sessions/{sid}/files`.
 * [offset] is the new server-side offset after this chunk; [length] is the byte
 * count the server accepted from the chunk.
 */
@JsonClass(generateAdapter = true)
data class ChunkResponse(
    @Json(name = "offset") val offset: Long,
    @Json(name = "length") val length: Long,
)

/** Response from `POST /api/sync/sessions/{sid}/complete`. */
@JsonClass(generateAdapter = true)
data class CompleteSessionResponse(
    @Json(name = "status") val status: String,
)

/** One row of `GET /api/sync/sessions/{sid}/outcomes`. */
@JsonClass(generateAdapter = true)
data class OutcomeDto(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "name") val name: String,
    @Json(name = "status") val status: String,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "retryable") val retryable: Boolean? = null,
)

/** Response from `GET /api/sync/sessions/{sid}/outcomes`. */
@JsonClass(generateAdapter = true)
data class OutcomesResponse(
    @Json(name = "outcomes") val outcomes: List<OutcomeDto>,
)

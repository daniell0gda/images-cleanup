package imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The wire identity used in reconcile, reconcile-results, verify and
 * verify-results bodies: `{name, created_on, size}`. Shared because the server
 * uses the identical shape across these endpoints.
 */
@JsonClass(generateAdapter = true)
data class IdentityDto(
    @Json(name = "name") val name: String,
    @Json(name = "created_on") val createdOn: String,
    @Json(name = "size") val size: Long,
)

/**
 * One result row of `POST /api/sync/reconcile`.
 *
 * When [alreadySynced] is false but bytes are already on the server from an
 * interrupted run, [uploadedOffset] is non-zero and [resumeSessionId] /
 * [resumeFileId] point at the incomplete session to resume into, so the file is
 * continued rather than re-uploaded from scratch.
 */
@JsonClass(generateAdapter = true)
data class ReconcileResultDto(
    @Json(name = "name") val name: String,
    @Json(name = "created_on") val createdOn: String,
    @Json(name = "size") val size: Long,
    @Json(name = "already_synced") val alreadySynced: Boolean,
    @Json(name = "uploaded_offset") val uploadedOffset: Long = 0,
    @Json(name = "resume_session_id") val resumeSessionId: String? = null,
    @Json(name = "resume_file_id") val resumeFileId: String? = null,
)

/** Response body of `POST /api/sync/reconcile`. */
@JsonClass(generateAdapter = true)
data class ReconcileResponse(
    @Json(name = "results") val results: List<ReconcileResultDto>,
)

/** One result row of `POST /api/sync/verify`. */
@JsonClass(generateAdapter = true)
data class VerifyResultDto(
    @Json(name = "name") val name: String,
    @Json(name = "created_on") val createdOn: String,
    @Json(name = "size") val size: Long,
    @Json(name = "present") val present: Boolean,
)

/** Response body of `POST /api/sync/verify`. */
@JsonClass(generateAdapter = true)
data class VerifyResponse(
    @Json(name = "results") val results: List<VerifyResultDto>,
)

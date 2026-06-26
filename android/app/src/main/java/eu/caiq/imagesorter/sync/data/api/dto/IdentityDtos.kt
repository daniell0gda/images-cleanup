package eu.caiq.imagesorter.sync.data.api.dto

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

/** One result row of `POST /api/sync/reconcile`. */
@JsonClass(generateAdapter = true)
data class ReconcileResultDto(
    @Json(name = "name") val name: String,
    @Json(name = "created_on") val createdOn: String,
    @Json(name = "size") val size: Long,
    @Json(name = "already_synced") val alreadySynced: Boolean,
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

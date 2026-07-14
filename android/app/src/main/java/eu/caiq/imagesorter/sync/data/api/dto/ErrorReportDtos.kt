package eu.caiq.imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One phone-side sync failure reported to `POST /api/errors/report`. The wire
 * keys are camelCase to match the launcher's `ErrorReportItem` model verbatim;
 * [message] is null when the reason carries no extra detail.
 */
@JsonClass(generateAdapter = true)
data class ErrorReportItemDto(
    @Json(name = "name") val name: String,
    @Json(name = "createdOn") val createdOn: String,
    @Json(name = "size") val size: Long,
    @Json(name = "reason") val reason: String,
    @Json(name = "retryable") val retryable: Boolean,
    @Json(name = "message") val message: String?,
    @Json(name = "failedAt") val failedAt: Long,
)

package eu.caiq.imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** One item of a `GET /api/media` timeline page. */
@JsonClass(generateAdapter = true)
data class MediaItemDto(
    val id: Long,
    val kind: String,
    @Json(name = "date_taken") val dateTaken: String,
    val width: Int? = null,
    val height: Int? = null,
)

/** A single keyset page of `GET /api/media`; `next_cursor` is null on the final page. */
@JsonClass(generateAdapter = true)
data class MediaPageDto(
    val items: List<MediaItemDto>,
    @Json(name = "next_cursor") val nextCursor: String? = null,
)

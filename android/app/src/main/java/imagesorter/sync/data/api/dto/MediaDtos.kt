package imagesorter.sync.data.api.dto

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

/**
 * A single keyset page of `GET /api/media`. `next_cursor` is null on the final
 * (oldest) page / when no older photos exist; `prev_cursor` is set when newer
 * photos exist above the first item (e.g. after a seek or in PREPEND mode) and
 * null once the latest has been reached.
 */
@JsonClass(generateAdapter = true)
data class MediaPageDto(
    val items: List<MediaItemDto>,
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "prev_cursor") val prevCursor: String? = null,
)

/**
 * Tree of available media dates from `GET /api/media/dates`:
 * year string → zero-padded month string → sorted day-number list,
 * e.g. `{ "2024": { "03": [1, 5, 12] } }`.
 */
typealias MediaDatesDto = Map<String, Map<String, List<Int>>>

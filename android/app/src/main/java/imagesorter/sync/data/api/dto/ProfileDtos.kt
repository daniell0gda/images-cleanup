package imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** One entry of `GET /api/sync/profiles`. */
@JsonClass(generateAdapter = true)
data class ProfileDto(
    @Json(name = "profile_id") val profileId: String,
    @Json(name = "display_name") val displayName: String,
)

/** Body of `POST /api/sync/profiles` — the user-entered profile name. */
@JsonClass(generateAdapter = true)
data class ProfileRequest(
    @Json(name = "name") val name: String,
)

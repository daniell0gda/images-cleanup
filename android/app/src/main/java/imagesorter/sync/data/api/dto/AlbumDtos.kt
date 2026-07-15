package imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One album entry from `GET /api/albums` (and the create/rename/add/remove
 * responses). `share_url` and `cover_media_id` are null when the album is
 * unshared / empty. `share_url` is server-built (§3.11) and must be used
 * verbatim — the app never constructs share URLs.
 */
@JsonClass(generateAdapter = true)
data class AlbumDto(
    val id: Long,
    val name: String,
    @Json(name = "created_by") val createdBy: String? = null,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "item_count") val itemCount: Int,
    @Json(name = "cover_media_id") val coverMediaId: Long? = null,
    val shared: Boolean,
    @Json(name = "share_url") val shareUrl: String? = null,
)

/** Body for `POST /api/albums` (create). */
@JsonClass(generateAdapter = true)
data class CreateAlbumBody(
    val name: String,
    @Json(name = "media_ids") val mediaIds: List<Long>,
    @Json(name = "created_by") val createdBy: String? = null,
)

/** Body for `PATCH /api/albums/{id}` (rename). */
@JsonClass(generateAdapter = true)
data class AlbumNameBody(val name: String)

/** Body for add/remove membership on `/api/albums/{id}/items`. */
@JsonClass(generateAdapter = true)
data class AlbumItemsBody(@Json(name = "media_ids") val mediaIds: List<Long>)

/** Response of `POST /api/albums/{id}/share`. `share_url` is server-built. */
@JsonClass(generateAdapter = true)
data class ShareDto(
    @Json(name = "share_token") val shareToken: String,
    @Json(name = "share_url") val shareUrl: String,
)

/** Response of `DELETE /api/albums/{id}`. */
@JsonClass(generateAdapter = true)
data class DeletedDto(val deleted: Boolean)

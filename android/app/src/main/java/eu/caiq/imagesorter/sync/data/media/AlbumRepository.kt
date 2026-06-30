package eu.caiq.imagesorter.sync.data.media

import eu.caiq.imagesorter.sync.data.api.AlbumApi
import eu.caiq.imagesorter.sync.data.api.dto.AlbumDto
import eu.caiq.imagesorter.sync.data.api.dto.AlbumItemsBody
import eu.caiq.imagesorter.sync.data.api.dto.AlbumNameBody
import eu.caiq.imagesorter.sync.data.api.dto.CreateAlbumBody
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.api.dto.ShareDto

/**
 * Entry point for the shared-albums API (spec §7.3). Maps each `/api/albums*`
 * endpoint to a typed suspend function. The server owns the public hostname:
 * [share] and [albums] expose the server-built `share_url` verbatim — the app
 * never constructs or templates share URLs (§3.11).
 */
class AlbumRepository(private val api: AlbumApi) {

    suspend fun albums(): List<AlbumDto> = api.albums()

    suspend fun create(name: String, mediaIds: List<Long>, createdBy: String?): AlbumDto =
        api.create(CreateAlbumBody(name = name, mediaIds = mediaIds, createdBy = createdBy))

    suspend fun rename(id: Long, name: String): AlbumDto =
        api.rename(id, AlbumNameBody(name))

    suspend fun delete(id: Long): Boolean = api.delete(id).deleted

    suspend fun addItems(id: Long, mediaIds: List<Long>): AlbumDto =
        api.addItems(id, AlbumItemsBody(mediaIds))

    suspend fun removeItems(id: Long, mediaIds: List<Long>): AlbumDto =
        api.removeItems(id, AlbumItemsBody(mediaIds))

    suspend fun items(id: Long): List<MediaItemDto> = api.items(id)

    /** Mints a fresh share token; the returned [ShareDto.shareUrl] is used verbatim. */
    suspend fun share(id: Long): ShareDto = api.share(id)

    suspend fun revoke(id: Long): Boolean = api.revoke(id).revoked
}

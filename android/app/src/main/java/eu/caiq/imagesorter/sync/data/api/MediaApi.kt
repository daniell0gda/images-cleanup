package eu.caiq.imagesorter.sync.data.api

import eu.caiq.imagesorter.sync.data.api.dto.AlbumDto
import eu.caiq.imagesorter.sync.data.api.dto.AlbumItemsBody
import eu.caiq.imagesorter.sync.data.api.dto.AlbumNameBody
import eu.caiq.imagesorter.sync.data.api.dto.CreateAlbumBody
import eu.caiq.imagesorter.sync.data.api.dto.DeletedDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaPageDto
import eu.caiq.imagesorter.sync.data.api.dto.RevokedDto
import eu.caiq.imagesorter.sync.data.api.dto.ShareDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit binding for the server media timeline (`GET /api/media`). The bearer
 * token is attached by [AuthInterceptor]. The cursor is opaque (base64) and must
 * be passed back verbatim; absent cursor = first page.
 */
interface MediaApi {

    @GET("api/media")
    suspend fun media(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("from_date") fromDate: String? = null,
        @Query("before") before: String? = null,
        @Query("profile") profile: String? = null,
    ): MediaPageDto

    /** Full year → month → day tree of dates that have at least one indexed photo. */
    @GET("api/media/dates")
    suspend fun dates(): MediaDatesDto
}

/**
 * Retrofit binding for the shared-albums API (spec §5.1). All routes require the
 * device bearer token, attached by [AuthInterceptor]. `share_url` in responses is
 * server-built (§3.11) and must be consumed verbatim — the app never templates it.
 */
interface AlbumApi {

    @GET("api/albums")
    suspend fun albums(): List<AlbumDto>

    @POST("api/albums")
    suspend fun create(@Body body: CreateAlbumBody): AlbumDto

    @PATCH("api/albums/{id}")
    suspend fun rename(@Path("id") id: Long, @Body body: AlbumNameBody): AlbumDto

    @DELETE("api/albums/{id}")
    suspend fun delete(@Path("id") id: Long): DeletedDto

    @POST("api/albums/{id}/items")
    suspend fun addItems(@Path("id") id: Long, @Body body: AlbumItemsBody): AlbumDto

    @HTTP(method = "DELETE", path = "api/albums/{id}/items", hasBody = true)
    suspend fun removeItems(@Path("id") id: Long, @Body body: AlbumItemsBody): AlbumDto

    @GET("api/albums/{id}/items")
    suspend fun items(@Path("id") id: Long): List<MediaItemDto>

    @POST("api/albums/{id}/share")
    suspend fun share(@Path("id") id: Long): ShareDto

    @DELETE("api/albums/{id}/share")
    suspend fun revoke(@Path("id") id: Long): RevokedDto
}

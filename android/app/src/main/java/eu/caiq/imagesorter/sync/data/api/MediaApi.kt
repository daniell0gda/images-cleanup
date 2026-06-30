package eu.caiq.imagesorter.sync.data.api

import eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaPageDto
import retrofit2.http.GET
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
    ): MediaPageDto

    /** Full year → month → day tree of dates that have at least one indexed photo. */
    @GET("api/media/dates")
    suspend fun dates(): MediaDatesDto
}

package imagesorter.sync.data.media

/**
 * Builds the authenticated content URLs for server media. Coil (thumb/preview)
 * and ExoPlayer (stream) fetch these directly, so this is the single, unit-
 * testable place that knows the `/api/media/{id}/...` shape and how the bearer
 * token is carried as a request header.
 */
class MediaUrls(baseUrl: String) {

    private val base: String = baseUrl.trimEnd('/')

    fun thumb(id: Long): String = "$base/api/media/$id/thumb"

    fun preview(id: Long): String = "$base/api/media/$id/preview"

    fun stream(id: Long): String = "$base/api/media/$id/stream"

    companion object {
        /**
         * The `Authorization` header map for media requests, or empty when no
         * token is stored (matching [imagesorter.sync.data.api.AuthInterceptor]'s
         * "omit header → server answers 401" behavior).
         */
        fun authHeaders(token: String?): Map<String, String> =
            if (token.isNullOrEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $token")
    }
}

package eu.caiq.imagesorter.sync.data.api

import eu.caiq.imagesorter.sync.data.prefs.SecurePrefs
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <token>` to every request whose path is not on
 * the unauthenticated allow-list (register + status — the device has no token
 * before it is trusted). If no token is stored, the header is omitted and the
 * server answers 401, which the caller treats as "must (re-)pair".
 *
 * The token is read fresh from [SecurePrefs] per request so a revocation +
 * re-pair takes effect without rebuilding the OkHttp client.
 */
class AuthInterceptor(
    private val securePrefs: SecurePrefs,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isUnauthenticated(request.url.encodedPath)) {
            return chain.proceed(request)
        }

        val token = securePrefs.getToken()
        val authed = if (token.isNullOrEmpty()) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(authed)
    }

    private fun isUnauthenticated(path: String): Boolean {
        // POST /api/sync/devices  and  GET /api/sync/devices/{id}/status
        if (path == "/api/sync/devices") return true
        return path.startsWith("/api/sync/devices/") && path.endsWith("/status")
    }
}

package imagesorter.sync.data.api

import imagesorter.sync.serverAddressToBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Outcome of parsing user-supplied server connection input.
 *
 * Input form: a single address string — either a full URL
 * (`https://media.example.com`) or a bare `host[:port]` that defaults to http.
 * A valid address is normalized to a base URL ending in `/`; anything blank or
 * not parseable as an HTTP(S) URL is rejected without contacting the network.
 */
sealed interface ParseResult {
    data class Valid(val baseUrl: String) : ParseResult
    data class Invalid(val reason: String) : ParseResult
}

/**
 * Outcome of probing the no-auth reachability endpoint.
 *
 * - [Success]: server responded 2xx.
 * - [BadResponse]: server responded with a non-2xx status (it is reachable but
 *   not the expected server / not healthy).
 * - [Unreachable]: the connection itself failed (host down, wrong port, no
 *   network).
 */
sealed interface ProbeResult {
    data object Success : ProbeResult
    data class BadResponse(val code: Int) : ProbeResult
    data object Unreachable : ProbeResult
}

object ServerProbe {

    /**
     * Parses an [address] into a normalized base URL. Pure and offline: it never
     * probes. A blank address, or one that is not a valid HTTP(S) URL once a
     * default scheme is applied (bad host, non-numeric / out-of-range port),
     * yields [ParseResult.Invalid].
     */
    fun parse(address: String): ParseResult {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return ParseResult.Invalid("Address is required")
        val baseUrl = serverAddressToBaseUrl(trimmed)
            ?: return ParseResult.Invalid("Address is required")
        if (baseUrl.toHttpUrlOrNull() == null) {
            return ParseResult.Invalid(
                "Enter a valid address, e.g. https://media.example.com or 192.168.0.5:7000"
            )
        }
        return ParseResult.Valid(baseUrl)
    }

    /**
     * Probes the no-auth reachability endpoint via [api]. Returns
     * [ProbeResult.Success] on a 2xx response, [ProbeResult.BadResponse] on a
     * non-2xx status, and [ProbeResult.Unreachable] when the connection fails.
     */
    suspend fun validate(api: SyncApi): ProbeResult =
        try {
            val response = api.ping()
            if (response.isSuccessful) ProbeResult.Success
            else ProbeResult.BadResponse(response.code())
        } catch (e: java.io.IOException) {
            ProbeResult.Unreachable
        }
}

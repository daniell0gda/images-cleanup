package eu.caiq.imagesorter.sync.data.api

import eu.caiq.imagesorter.sync.serverAddressToBaseUrl

/**
 * Outcome of parsing user-supplied server connection input.
 *
 * Input form: a host string plus a numeric port string. A valid pair is
 * normalized to the base URL `http://<host>:<port>/`; anything blank or with a
 * non-numeric / out-of-range port is rejected without contacting the network.
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
     * Parses a [host] and [port] into a normalized base URL. Pure and offline:
     * it never probes. Blank host or a non-numeric / out-of-range (1..65535)
     * port yields [ParseResult.Invalid].
     */
    fun parse(host: String, port: String): ParseResult {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) return ParseResult.Invalid("Host is required")
        val portNumber = port.trim().toIntOrNull()
            ?: return ParseResult.Invalid("Port must be a number")
        if (portNumber !in 1..65535) return ParseResult.Invalid("Port must be between 1 and 65535")
        val baseUrl = serverAddressToBaseUrl("$trimmedHost:$portNumber")
            ?: return ParseResult.Invalid("Host and port are required")
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

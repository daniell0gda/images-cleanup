package eu.caiq.imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Body for `POST /api/sync/devices`. */
@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "name") val name: String,
)

/** Response from `POST /api/sync/devices` — always `status:"pending"`. */
@JsonClass(generateAdapter = true)
data class RegisterDeviceResponse(
    @Json(name = "status") val status: String,
    @Json(name = "pairing_code") val pairingCode: String,
)

/**
 * Response from `GET /api/sync/devices/{id}/status`.
 *
 * `status` is one of `pending` | `revoked` | `trusted`. [token] is present only
 * when `status == "trusted"`. A 404 (unknown device) is handled as an HTTP error,
 * not represented here.
 */
@JsonClass(generateAdapter = true)
data class DeviceStatusResponse(
    @Json(name = "status") val status: String,
    @Json(name = "token") val token: String? = null,
)

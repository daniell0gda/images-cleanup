package eu.caiq.imagesorter.sync.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Body for `POST /api/sync/devices`. */
@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "name") val name: String,
)

/**
 * Response from `POST /api/sync/devices`. Normally `status:"pending"` with a
 * fresh [pairingCode]; re-registering an already-trusted device is a server
 * no-op that returns `status:"trusted"` with NO code, so [pairingCode] is
 * nullable.
 */
@JsonClass(generateAdapter = true)
data class RegisterDeviceResponse(
    @Json(name = "status") val status: String,
    @Json(name = "pairing_code") val pairingCode: String? = null,
)

/**
 * Response from `GET /api/sync/devices/{id}/status`.
 *
 * `status` is one of `pending` | `revoked` | `trusted`. [token] is present only
 * when `status == "trusted"`; [pairingCode] only when `status == "pending"`. A 404
 * (unknown device) is handled as an HTTP error, not represented here.
 */
@JsonClass(generateAdapter = true)
data class DeviceStatusResponse(
    @Json(name = "status") val status: String,
    @Json(name = "token") val token: String? = null,
    @Json(name = "pairing_code") val pairingCode: String? = null,
)

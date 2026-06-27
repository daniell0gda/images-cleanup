package eu.caiq.imagesorter.sync.pairing

import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.dto.RegisterDeviceRequest
import eu.caiq.imagesorter.sync.data.prefs.CredentialStore
import kotlinx.coroutines.delay
import retrofit2.HttpException

/** The phone's view of the device's trust state during pairing. */
sealed interface PairingState {
    /** Registered; show [pairingCode] and keep polling. */
    data class Pending(val pairingCode: String) : PairingState

    /** Approved on the launcher; token stored. */
    data object Trusted : PairingState

    /** Revoked on the launcher; the user must re-pair. */
    data object Revoked : PairingState
}

/**
 * Owns the first-run pairing handshake:
 *
 *  1. mint/read the per-install device id + a friendly name,
 *  2. `POST /devices` to register (returns the 6-digit code to display),
 *  3. poll `GET /devices/{id}/status` until `trusted` (then store the token) or
 *     `revoked`,
 *
 * Approval itself happens on the launcher UI — the phone never calls approve.
 */
class PairingManager(
    private val api: SyncApi,
    private val securePrefs: CredentialStore,
    private val deviceName: String,
) {
    /**
     * Register this device. Returns the pairing code to display.
     *
     * Note: on the launcher this *resets* the device to pending and mints a new
     * code, so it must only be called for a device the server does not already
     * know — see [beginPairing], which guards this.
     */
    suspend fun register(): String {
        val deviceId = securePrefs.getOrCreateDeviceId()
        val response = api.registerDevice(RegisterDeviceRequest(deviceId = deviceId, name = deviceName))
        return response.pairingCode
    }

    /**
     * Resolve the device's pairing state for display, registering only when the
     * server does not yet know this device (a 404 on status).
     *
     * This is the safe entry point: it never re-registers a device the launcher
     * already knows, so a phone that lost its local token (e.g. the user cleared
     * app data) recovers its existing trust — or its existing pending code —
     * instead of resetting the device and forcing a fresh approval.
     */
    suspend fun beginPairing(): PairingState {
        val current = try {
            checkStatus()
        } catch (e: HttpException) {
            if (e.code() == HTTP_NOT_FOUND) null else throw e
        }
        return when {
            current == null -> PairingState.Pending(register())
            current is PairingState.Pending && current.pairingCode.isBlank() ->
                PairingState.Pending(register())
            else -> current
        }
    }

    /**
     * Single status check. On `trusted`, the token is persisted here so callers
     * only need to react to the returned state.
     */
    suspend fun checkStatus(): PairingState {
        val deviceId = securePrefs.getOrCreateDeviceId()
        val response = api.deviceStatus(deviceId)
        return when (response.status) {
            STATUS_TRUSTED -> {
                response.token?.let { securePrefs.setToken(it) }
                PairingState.Trusted
            }
            STATUS_REVOKED -> {
                securePrefs.clearTokenForRepair()
                PairingState.Revoked
            }
            else -> PairingState.Pending(pairingCode = response.pairingCode.orEmpty())
        }
    }

    /**
     * Poll status until the device becomes trusted or revoked. Emits each state
     * via [onState] so the UI can keep showing the code while pending. A 404
     * (server forgot the device) surfaces as [PairingState.Revoked] so the flow
     * restarts cleanly.
     */
    suspend fun pollUntilResolved(
        intervalMillis: Long = POLL_INTERVAL_MS,
        onState: (PairingState) -> Unit,
    ): PairingState {
        while (true) {
            val state = try {
                checkStatus()
            } catch (e: HttpException) {
                if (e.code() == HTTP_NOT_FOUND) PairingState.Revoked else throw e
            }
            onState(state)
            if (state !is PairingState.Pending) return state
            delay(intervalMillis)
        }
    }

    companion object {
        private const val STATUS_TRUSTED = "trusted"
        private const val STATUS_REVOKED = "revoked"
        private const val HTTP_NOT_FOUND = 404
        private const val POLL_INTERVAL_MS = 3_000L
    }
}

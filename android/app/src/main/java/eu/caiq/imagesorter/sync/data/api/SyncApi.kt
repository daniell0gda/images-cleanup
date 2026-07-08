package eu.caiq.imagesorter.sync.data.api

import eu.caiq.imagesorter.sync.data.api.dto.ChunkResponse
import eu.caiq.imagesorter.sync.data.api.dto.CompleteSessionResponse
import eu.caiq.imagesorter.sync.data.api.dto.DeviceStatusResponse
import eu.caiq.imagesorter.sync.data.api.dto.FileOffsetResponse
import eu.caiq.imagesorter.sync.data.api.dto.IdentityDto
import eu.caiq.imagesorter.sync.data.api.dto.OpenSessionRequest
import eu.caiq.imagesorter.sync.data.api.dto.OpenSessionResponse
import eu.caiq.imagesorter.sync.data.api.dto.OutcomesResponse
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.data.api.dto.ReconcileResponse
import eu.caiq.imagesorter.sync.data.api.dto.RegisterDeviceRequest
import eu.caiq.imagesorter.sync.data.api.dto.RegisterDeviceResponse
import eu.caiq.imagesorter.sync.data.api.dto.VerifyResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit binding for the NAS sync server (base `http://<nas-host>:7000`).
 *
 * The bearer token is attached by [AuthInterceptor] for every call **except**
 * register and status, which are reachable without auth (the device has no token
 * until it is trusted). The chunk-upload endpoint uses a raw request body and is
 * driven by [UploadClient]; it is intentionally not exposed as a typed body here
 * because Retrofit's `@Body RequestBody` plus per-chunk headers is cleaner from
 * the uploader's streaming code.
 */
interface SyncApi {

    // --- Reachability probe (no auth) ---

    /**
     * Unauthenticated reachability probe. The launcher's `GET /api/ping` needs
     * no token and is part of the public surface (reachable through the reverse
     * proxy), so this works over both LAN and the public HTTPS address; we only
     * inspect the HTTP status to decide whether the server is reachable.
     */
    @GET("api/ping")
    suspend fun ping(): retrofit2.Response<okhttp3.ResponseBody>

    // --- Pairing (no auth) ---

    @POST("api/sync/devices")
    suspend fun registerDevice(
        @Body body: RegisterDeviceRequest,
    ): RegisterDeviceResponse

    /**
     * Poll a device's trust state. The pairing code travels as the
     * `X-Pairing-Code` header; the server only returns the bearer token when it
     * matches the device's stored code. A null code omits the header (no token).
     */
    @GET("api/sync/devices/{deviceId}/status")
    suspend fun deviceStatus(
        @Path("deviceId") deviceId: String,
        @Header("X-Pairing-Code") pairingCode: String?,
    ): DeviceStatusResponse

    // --- Profiles ---

    @GET("api/sync/profiles")
    suspend fun profiles(): List<ProfileDto>

    /**
     * Create a profile from a user-entered name. Unauthenticated, like [profiles].
     * The server returns the created row; a 409 signals a duplicate name and a 400
     * an invalid one, which callers map to distinct messages.
     */
    @POST("api/sync/profiles")
    suspend fun createProfile(
        @Body body: eu.caiq.imagesorter.sync.data.api.dto.ProfileRequest,
    ): ProfileDto

    // --- Reconcile / verify ---

    @POST("api/sync/reconcile")
    suspend fun reconcile(
        @Body identities: List<IdentityDto>,
    ): ReconcileResponse

    @POST("api/sync/verify")
    suspend fun verify(
        @Body identities: List<IdentityDto>,
    ): VerifyResponse

    // --- Sessions ---

    @POST("api/sync/sessions")
    suspend fun openSession(
        @Body body: OpenSessionRequest,
    ): OpenSessionResponse

    @GET("api/sync/sessions/{sessionId}/files/{fileId}")
    suspend fun fileOffset(
        @Path("sessionId") sessionId: String,
        @Path("fileId") fileId: String,
    ): FileOffsetResponse

    /**
     * Upload one chunk. RAW BODY = the chunk bytes; all file metadata travels in
     * headers. [UploadClient] builds the streaming [RequestBody] and headers.
     */
    @POST("api/sync/sessions/{sessionId}/files")
    suspend fun uploadChunk(
        @Path("sessionId") sessionId: String,
        @Header("File-Id") fileId: String,
        @Header("File-Name") fileName: String,
        @Header("File-Created-On") fileCreatedOn: String,
        @Header("File-Size") fileSize: Long,
        @Header("File-Mime-Type") fileMimeType: String,
        @Header("Upload-Offset") uploadOffset: Long,
        @Body chunk: RequestBody,
    ): ChunkResponse

    @POST("api/sync/sessions/{sessionId}/complete")
    suspend fun completeSession(
        @Path("sessionId") sessionId: String,
    ): CompleteSessionResponse

    @GET("api/sync/sessions/{sessionId}/outcomes")
    suspend fun outcomes(
        @Path("sessionId") sessionId: String,
    ): OutcomesResponse
}

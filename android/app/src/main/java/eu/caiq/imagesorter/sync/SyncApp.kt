package eu.caiq.imagesorter.sync

import android.app.Application
import android.content.Context
import android.os.Build
import com.squareup.moshi.Moshi
import eu.caiq.imagesorter.sync.data.api.AlbumApi
import eu.caiq.imagesorter.sync.data.api.AuthInterceptor
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.UploadClient
import eu.caiq.imagesorter.sync.data.db.AppDatabase
import eu.caiq.imagesorter.sync.data.media.AlbumRepository
import eu.caiq.imagesorter.sync.data.media.MediaRepository
import eu.caiq.imagesorter.sync.data.media.MediaStoreScanner
import eu.caiq.imagesorter.sync.data.prefs.SecurePrefs
import eu.caiq.imagesorter.sync.pairing.PairingManager
import eu.caiq.imagesorter.sync.sync.CleanupManager
import eu.caiq.imagesorter.sync.sync.ManualSyncTrigger
import eu.caiq.imagesorter.sync.sync.SyncEngine
import eu.caiq.imagesorter.sync.sync.SyncTrigger
import eu.caiq.imagesorter.sync.sync.TusUploader
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Application entry point. Hosts a single [ServiceLocator] — a deliberately
 * simple, hand-rolled DI container. A skeleton does not need Hilt; lazy
 * singletons here keep the wiring explicit and the graph easy to read.
 */
class SyncApp : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator(this)
    }
}

/**
 * Derives the Retrofit base URL from a stored `host:port` server address.
 * Returns null when no address is configured so callers can defer building the
 * api client rather than targeting any hardcoded default.
 */
fun serverAddressToBaseUrl(address: String?): String? {
    val trimmed = address?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // Respect an explicit scheme (e.g. an HTTPS domain behind a reverse proxy);
    // otherwise default to http:// so a bare `host:port` keeps working.
    val withScheme =
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
}

/**
 * Manual dependency graph. Everything is a lazy singleton scoped to the process.
 *
 * The base URL comes from the user-configured server address stored in
 * [SecurePrefs]; the api client is rebuilt whenever that address changes.
 */
class ServiceLocator(private val app: Context) {

    // --- Persistence / prefs ---

    val securePrefs: SecurePrefs by lazy { SecurePrefs(app) }

    private val database: AppDatabase by lazy { AppDatabase.build(app) }

    // --- Networking ---

    private val moshi: Moshi by lazy { Moshi.Builder().build() }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(securePrefs))
            // Large transfers: generous read/write windows, no call timeout. The
            // read window also covers `complete`, which blocks while the server
            // classifies + places a whole upload batch, so it is generous.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private var cachedApi: SyncApi? = null
    private var cachedBaseUrl: String? = null

    /**
     * Retrofit-backed [SyncApi]. Rebuilt whenever the stored server address
     * changes so a re-pointed address takes effect without a process restart.
     * Falls back to a placeholder base URL only when no address is stored yet,
     * deferring real targeting until the user configures one.
     */
    val api: SyncApi
        get() {
            val baseUrl = serverAddressToBaseUrl(securePrefs.getServerAddress()) ?: PLACEHOLDER_BASE_URL
            val existing = cachedApi
            if (existing != null && baseUrl == cachedBaseUrl) return existing
            val built = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SyncApi::class.java)
            cachedApi = built
            cachedBaseUrl = baseUrl
            return built
        }

    private var cachedMediaApi: MediaApi? = null
    private var cachedMediaBaseUrl: String? = null

    /**
     * Retrofit-backed [MediaApi] for the server timeline. Rebuilt on address
     * change like [api], sharing the same OkHttp/Auth/Moshi wiring so the bearer
     * token rides along on `GET /api/media`.
     */
    val mediaApi: MediaApi
        get() {
            val baseUrl = serverAddressToBaseUrl(securePrefs.getServerAddress()) ?: PLACEHOLDER_BASE_URL
            val existing = cachedMediaApi
            if (existing != null && baseUrl == cachedMediaBaseUrl) return existing
            val built = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(MediaApi::class.java)
            cachedMediaApi = built
            cachedMediaBaseUrl = baseUrl
            return built
        }

    /**
     * Server media timeline as Paging 3 (Room `RemoteMediator`). Cluster 10's
     * Photos screen observes [MediaRepository.timeline] and applies day headers.
     */
    val mediaRepository: MediaRepository by lazy { MediaRepository(mediaApi, database) }

    private var cachedAlbumApi: AlbumApi? = null
    private var cachedAlbumBaseUrl: String? = null

    /**
     * Retrofit-backed [AlbumApi] for the shared-albums API. Rebuilt on address
     * change like [mediaApi], sharing the same OkHttp/Auth/Moshi wiring so the
     * bearer token rides along on `/api/albums*`.
     */
    private val albumApi: AlbumApi
        get() {
            val baseUrl = serverAddressToBaseUrl(securePrefs.getServerAddress()) ?: PLACEHOLDER_BASE_URL
            val existing = cachedAlbumApi
            if (existing != null && baseUrl == cachedAlbumBaseUrl) return existing
            val built = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(AlbumApi::class.java)
            cachedAlbumApi = built
            cachedAlbumBaseUrl = baseUrl
            return built
        }

    /** Shared-albums repository for the Photos selection actions and Albums tab. */
    val albumRepository: AlbumRepository by lazy { AlbumRepository(albumApi) }

    /**
     * Builds a throwaway [SyncApi] targeting [baseUrl], reusing the shared
     * OkHttp/Moshi/Auth wiring. Used to probe an entered address before it is
     * persisted, so a failed probe never re-points the cached [api].
     */
    fun buildApi(baseUrl: String): SyncApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SyncApi::class.java)

    private val uploadClient: UploadClient by lazy {
        UploadClient(api, app.contentResolver)
    }

    // --- Media ---

    private val scanner: MediaStoreScanner by lazy { MediaStoreScanner(app) }

    // --- Sync core ---

    private val uploader: TusUploader by lazy { TusUploader(api, uploadClient) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            api = api,
            scanner = scanner,
            uploader = uploader,
            securePrefs = securePrefs,
            syncedCacheDao = database.syncedCacheDao(),
            pendingUploadDao = database.pendingUploadDao(),
            failureDao = database.failureDao(),
        )
    }

    val cleanupManager: CleanupManager by lazy {
        CleanupManager(api, database.syncedCacheDao(), app.contentResolver)
    }

    val pairingManager: PairingManager by lazy {
        PairingManager(api, securePrefs, deviceName = defaultDeviceName())
    }

    /** v1 trigger: user-initiated foreground sync. Typed as the seam interface. */
    val syncTrigger: SyncTrigger by lazy { ManualSyncTrigger(app) }

    // --- Observables for the UI ---

    fun syncedCacheDao() = database.syncedCacheDao()
    fun pendingUploadDao() = database.pendingUploadDao()
    fun failureDao() = database.failureDao()

    private fun defaultDeviceName(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android device" }

    companion object {
        // Inert stand-in used only while no server address is stored, so Retrofit
        // can be constructed without targeting any real host. Replaced as soon as
        // the user configures an address.
        private const val PLACEHOLDER_BASE_URL = "http://localhost/"
    }
}

package eu.caiq.imagesorter.sync

import android.app.Application
import android.content.Context
import android.os.Build
import com.squareup.moshi.Moshi
import eu.caiq.imagesorter.sync.data.api.AuthInterceptor
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.UploadClient
import eu.caiq.imagesorter.sync.data.db.AppDatabase
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
 * Manual dependency graph. Everything is a lazy singleton scoped to the process.
 *
 * The base URL is a stand-in for a user-configured NAS host; settings should let
 * the user set host/port. For the skeleton it points at a LAN default.
 *
 * TODO(designer): surface host/port and concurrency in a Settings screen.
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
            // Large transfers: generous read/write windows, no call timeout.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    val api: SyncApi by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SyncApi::class.java)
    }

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

    private fun baseUrl(): String {
        // TODO(designer): make host/port user-configurable in Settings.
        return "http://$DEFAULT_HOST:$DEFAULT_PORT/"
    }

    private fun defaultDeviceName(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android device" }

    companion object {
        private const val DEFAULT_HOST = "nas.local"
        private const val DEFAULT_PORT = 7000
    }
}

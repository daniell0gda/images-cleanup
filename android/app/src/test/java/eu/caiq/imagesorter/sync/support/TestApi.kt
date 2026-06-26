package eu.caiq.imagesorter.sync.support

import com.squareup.moshi.Moshi
import eu.caiq.imagesorter.sync.data.api.AuthInterceptor
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.media.MediaSource
import eu.caiq.imagesorter.sync.data.prefs.CredentialStore
import eu.caiq.imagesorter.sync.data.prefs.SyncPrefs
import eu.caiq.imagesorter.sync.data.prefs.TokenStore
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** In-memory [TokenStore] for interceptor/pairing tests. */
class FakeTokenStore(private var value: String? = null) : TokenStore {
    override fun getToken(): String? = value
}

/** In-memory [CredentialStore] for pairing/engine tests. */
class FakeCredentialStore(
    private val deviceId: String = "dev-test",
    initialToken: String? = null,
) : CredentialStore {
    var currentToken: String? = initialToken
        private set

    var repairCleared = false
        private set

    override fun getOrCreateDeviceId(): String = deviceId
    override fun getToken(): String? = currentToken
    override fun setToken(token: String?) { currentToken = token }
    override fun clearTokenForRepair() {
        currentToken = null
        repairCleared = true
    }
}

/** In-memory [SyncPrefs] for SyncEngine tests. */
class FakeSyncPrefs(
    private val profileId: String? = "groupby",
    private val concurrency: Int = 1,
) : SyncPrefs {
    var mediaGeneration: Long? = null
        private set
    var repairCleared = false
        private set

    override fun getProfileId(): String? = profileId
    override fun getUploadConcurrency(): Int = concurrency
    override fun setMediaGeneration(value: Long) { mediaGeneration = value }
    override fun clearTokenForRepair() { repairCleared = true }
}

/** Canned [MediaSource] returning a fixed item list for SyncEngine tests. */
class FakeMediaSource(
    private val items: List<MediaItem>,
    private val generation: Long = 0,
) : MediaSource {
    override fun enumerate(sinceGeneration: Long, folders: Set<String>): List<MediaItem> = items
    override fun currentGeneration(): Long = generation
}

/** Builds a [SyncApi] pointed at [server], optionally with an [AuthInterceptor]. */
fun buildSyncApi(server: MockWebServer, tokenStore: TokenStore? = null): SyncApi {
    val clientBuilder = OkHttpClient.Builder()
    if (tokenStore != null) {
        clientBuilder.addInterceptor(AuthInterceptor(tokenStore))
    }
    return Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(clientBuilder.build())
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
        .build()
        .create(SyncApi::class.java)
}

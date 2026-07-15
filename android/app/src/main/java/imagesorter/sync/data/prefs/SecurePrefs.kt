package imagesorter.sync.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Persistent settings store.
 *
 * Secrets (bearer [token] and the per-install [deviceId]) live in a
 * Keystore-backed [EncryptedSharedPreferences]. Non-secret cursors and choices
 * (profile id, MediaStore generation watermark, trusted-network preference) live
 * in a plain [SharedPreferences] — they are rebuildable and not sensitive.
 *
 * The device id is derived from [Settings.Secure.ANDROID_ID] so it survives the
 * user clearing the app's data (which wipes the encrypted store) — the phone then
 * recovers its existing pairing instead of re-pairing. A previously stored id is
 * kept as-is for backward compatibility with installs that predate this.
 */
class SecurePrefs(context: Context) : CredentialStore, SyncPrefs, imagesorter.sync.ui.RoutingPrefs {

    private val appContext = context.applicationContext

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences by lazy {
        appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
    }

    // --- Secrets ---

    /**
     * Stable device id. Honors any id already stored by an earlier app version;
     * otherwise derives one from ANDROID_ID (stable across app-data clears), and
     * only falls back to a random UUID on the rare device that reports no
     * ANDROID_ID. The resolved value is cached so it never changes mid-install.
     */
    override fun getOrCreateDeviceId(): String {
        secure.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = stableDeviceId()
        secure.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private fun stableDeviceId(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        return if (!androidId.isNullOrBlank()) "android-$androidId" else UUID.randomUUID().toString()
    }

    override fun getToken(): String? = secure.getString(KEY_TOKEN, null)

    override fun setToken(token: String?) {
        secure.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    override fun getPairingCode(): String? = secure.getString(KEY_PAIRING_CODE, null)

    override fun setPairingCode(code: String?) {
        secure.edit().apply {
            if (code == null) remove(KEY_PAIRING_CODE) else putString(KEY_PAIRING_CODE, code)
        }.apply()
    }

    override fun isTrusted(): Boolean = !getToken().isNullOrEmpty()

    /** Clears the token (e.g. after a 401) so the app routes back to pairing. */
    override fun clearTokenForRepair() = setToken(null)

    // --- Non-secret choices / cursors ---

    override fun getProfileId(): String? = plain.getString(KEY_PROFILE_ID, null)

    override fun setProfileId(value: String) {
        plain.edit().putString(KEY_PROFILE_ID, value).apply()
    }

    override fun clearProfileId() {
        plain.edit().remove(KEY_PROFILE_ID).apply()
    }

    /**
     * The last MediaStore generation processed. A value < 0 means "no watermark"
     * and forces a full enumerate on the next discovery.
     */
    override fun getMediaGeneration(): Long = plain.getLong(KEY_MEDIA_GENERATION, NO_WATERMARK)

    override fun setMediaGeneration(value: Long) {
        plain.edit().putLong(KEY_MEDIA_GENERATION, value).apply()
    }

    /** Wall-clock completion time of the last full sync, or null if none has run. */
    override fun getLastFullSyncAtMillis(): Long? =
        if (plain.contains(KEY_LAST_FULL_SYNC_AT)) plain.getLong(KEY_LAST_FULL_SYNC_AT, 0L) else null

    override fun setLastFullSyncAtMillis(value: Long) {
        plain.edit().putLong(KEY_LAST_FULL_SYNC_AT, value).apply()
    }

    override fun getServerAddress(): String? = plain.getString(KEY_SERVER_ADDRESS, null)

    override fun setServerAddress(value: String?) {
        plain.edit().apply {
            if (value == null) remove(KEY_SERVER_ADDRESS) else putString(KEY_SERVER_ADDRESS, value)
        }.apply()
    }

    /** The SSID the user marked as trusted for sync, or null if none. */
    fun getTrustedSsid(): String? = plain.getString(KEY_TRUSTED_SSID, null)

    fun setTrustedSsid(ssid: String?) {
        plain.edit().apply {
            if (ssid == null) remove(KEY_TRUSTED_SSID) else putString(KEY_TRUSTED_SSID, ssid)
        }.apply()
    }

    /** Configurable upload concurrency (4–10, default 6). */
    override fun getUploadConcurrency(): Int = plain.getInt(KEY_UPLOAD_CONCURRENCY, DEFAULT_CONCURRENCY)

    fun setUploadConcurrency(value: Int) {
        plain.edit().putInt(KEY_UPLOAD_CONCURRENCY, value.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)).apply()
    }

    companion object {
        private const val SECURE_FILE = "secure_prefs"
        private const val PLAIN_FILE = "sync_prefs"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_MEDIA_GENERATION = "media_generation"
        private const val KEY_LAST_FULL_SYNC_AT = "last_full_sync_at"
        private const val KEY_TRUSTED_SSID = "trusted_ssid"
        private const val KEY_UPLOAD_CONCURRENCY = "upload_concurrency"
        private const val KEY_SERVER_ADDRESS = "server_address"

        const val NO_WATERMARK = -1L
        const val MIN_CONCURRENCY = 4
        const val MAX_CONCURRENCY = 10
        const val DEFAULT_CONCURRENCY = 6
    }
}

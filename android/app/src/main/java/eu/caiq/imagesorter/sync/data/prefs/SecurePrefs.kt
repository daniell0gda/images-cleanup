package eu.caiq.imagesorter.sync.data.prefs

import android.content.Context
import android.content.SharedPreferences
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
 * The device id is generated once on first access and never changes for the life
 * of the install; uninstall/reinstall mints a new identity and re-pairs.
 */
class SecurePrefs(context: Context) {

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

    /** Stable per-install device UUID, generated lazily on first call. */
    fun getOrCreateDeviceId(): String {
        secure.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        secure.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun getToken(): String? = secure.getString(KEY_TOKEN, null)

    fun setToken(token: String?) {
        secure.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    fun isTrusted(): Boolean = !getToken().isNullOrEmpty()

    /** Clears the token (e.g. after a 401) so the app routes back to pairing. */
    fun clearTokenForRepair() = setToken(null)

    // --- Non-secret choices / cursors ---

    fun getProfileId(): String? = plain.getString(KEY_PROFILE_ID, null)

    fun setProfileId(profileId: String) {
        plain.edit().putString(KEY_PROFILE_ID, profileId).apply()
    }

    /**
     * The last MediaStore generation processed. A value < 0 means "no watermark"
     * and forces a full enumerate on the next discovery.
     */
    fun getMediaGeneration(): Long = plain.getLong(KEY_MEDIA_GENERATION, NO_WATERMARK)

    fun setMediaGeneration(value: Long) {
        plain.edit().putLong(KEY_MEDIA_GENERATION, value).apply()
    }

    /** The SSID the user marked as trusted for sync, or null if none. */
    fun getTrustedSsid(): String? = plain.getString(KEY_TRUSTED_SSID, null)

    fun setTrustedSsid(ssid: String?) {
        plain.edit().apply {
            if (ssid == null) remove(KEY_TRUSTED_SSID) else putString(KEY_TRUSTED_SSID, ssid)
        }.apply()
    }

    /** Configurable upload concurrency (4–10, default 6). */
    fun getUploadConcurrency(): Int = plain.getInt(KEY_UPLOAD_CONCURRENCY, DEFAULT_CONCURRENCY)

    fun setUploadConcurrency(value: Int) {
        plain.edit().putInt(KEY_UPLOAD_CONCURRENCY, value.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)).apply()
    }

    companion object {
        private const val SECURE_FILE = "secure_prefs"
        private const val PLAIN_FILE = "sync_prefs"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_MEDIA_GENERATION = "media_generation"
        private const val KEY_TRUSTED_SSID = "trusted_ssid"
        private const val KEY_UPLOAD_CONCURRENCY = "upload_concurrency"

        const val NO_WATERMARK = -1L
        const val MIN_CONCURRENCY = 4
        const val MAX_CONCURRENCY = 10
        const val DEFAULT_CONCURRENCY = 6
    }
}

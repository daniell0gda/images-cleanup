package eu.caiq.imagesorter.sync.data.prefs

/**
 * Settings seam needed by [eu.caiq.imagesorter.sync.sync.SyncEngine] (chosen
 * profile, upload concurrency, MediaStore watermark, token clear-for-repair),
 * implemented by [SecurePrefs]. Lets the engine be unit tested without the
 * Keystore-backed concrete store.
 */
interface SyncPrefs {
    fun getProfileId(): String?

    /** Forget the chosen profile (e.g. session-open reported it removed server-side). */
    fun clearProfileId()
    fun getUploadConcurrency(): Int

    /** The persisted MediaStore generation watermark (or [SecurePrefs.NO_WATERMARK]). */
    fun getMediaGeneration(): Long
    fun setMediaGeneration(value: Long)

    /** Wall-clock time the last full sync completed, or null if none has. */
    fun getLastFullSyncAtMillis(): Long?
    fun setLastFullSyncAtMillis(value: Long)
    fun clearTokenForRepair()

    /** Stored `host:port` of the configured server, or null when unset. */
    fun getServerAddress(): String?
    fun setServerAddress(value: String?)
}

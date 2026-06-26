package eu.caiq.imagesorter.sync.data.prefs

/**
 * Settings seam needed by [eu.caiq.imagesorter.sync.sync.SyncEngine] (chosen
 * profile, upload concurrency, MediaStore watermark, token clear-for-repair),
 * implemented by [SecurePrefs]. Lets the engine be unit tested without the
 * Keystore-backed concrete store.
 */
interface SyncPrefs {
    fun getProfileId(): String?
    fun getUploadConcurrency(): Int
    fun setMediaGeneration(value: Long)
    fun clearTokenForRepair()
}

package eu.caiq.imagesorter.sync.data.prefs

/**
 * Read/write seam for the device credentials ([TokenStore] plus the device id and
 * token mutations), implemented by [SecurePrefs]. Lets pairing/sync logic be unit
 * tested without the Keystore-backed concrete store.
 */
interface CredentialStore : TokenStore {
    fun getOrCreateDeviceId(): String
    fun setToken(token: String?)
    fun clearTokenForRepair()
}

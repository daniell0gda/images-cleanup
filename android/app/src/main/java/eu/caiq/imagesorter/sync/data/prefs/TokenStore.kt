package eu.caiq.imagesorter.sync.data.prefs

/**
 * Read seam for the bearer token, implemented by [SecurePrefs]. Lets
 * [eu.caiq.imagesorter.sync.data.api.AuthInterceptor] read the token without
 * depending on the Keystore-backed concrete store, so it is unit-testable.
 */
interface TokenStore {
    fun getToken(): String?
}

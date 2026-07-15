package imagesorter.sync.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the AppRoot entry effect: it starts the pairing poll only on the
 * PAIRING screen, never while the user is on SERVER_SETUP.
 */
class AppRootRoutingTest {

    @Test
    fun startsPairingOnlyOnPairingScreen() {
        assertTrue(shouldStartPairing(AppScreen.PAIRING))
    }

    @Test
    fun doesNotStartPairingDuringServerSetup() {
        assertFalse(shouldStartPairing(AppScreen.SERVER_SETUP))
    }

    @Test
    fun doesNotStartPairingOnOtherScreens() {
        assertFalse(shouldStartPairing(AppScreen.PROFILE_PICKER))
        assertFalse(shouldStartPairing(AppScreen.MAIN))
        assertFalse(shouldStartPairing(AppScreen.CLEANUP))
    }
}

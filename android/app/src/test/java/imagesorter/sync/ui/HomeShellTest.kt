package imagesorter.sync.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the navigation shell model: which top-level screens render the bottom
 * NavigationBar (the post-pairing home screens) versus full-screen (server
 * setup / pairing).
 */
class HomeShellTest {

    @Test
    fun bottomBarShowsOnPostPairingHomeScreens() {
        assertTrue(showsBottomBar(AppScreen.PROFILE_PICKER))
        assertTrue(showsBottomBar(AppScreen.MAIN))
        assertTrue(showsBottomBar(AppScreen.CLEANUP))
    }

    @Test
    fun bottomBarHiddenDuringServerSetupAndPairing() {
        assertFalse(showsBottomBar(AppScreen.SERVER_SETUP))
        assertFalse(showsBottomBar(AppScreen.PAIRING))
    }
}

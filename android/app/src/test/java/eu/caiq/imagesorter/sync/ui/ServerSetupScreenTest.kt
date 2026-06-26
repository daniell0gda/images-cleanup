package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import eu.caiq.imagesorter.sync.ui.screens.ServerSetupScreen
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerSetupScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersHostAndPortInputsAndConnectButton() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = null, onConnect = { _, _ -> })
            }
        }
        composeRule.onNodeWithText("Host").assertExists()
        composeRule.onNodeWithText("Port").assertExists()
        composeRule.onNodeWithText("Connect").assertExists()
    }

    @Test
    fun tappingConnectInvokesCallbackWithEnteredHostAndPort() {
        var connectedHost: String? = null
        var connectedPort: String? = null
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(
                    error = null,
                    onConnect = { h, p -> connectedHost = h; connectedPort = p },
                )
            }
        }
        composeRule.onNodeWithText("Host").performTextInput("192.168.1.50")
        composeRule.onNodeWithText("Port").performTextClearance()
        composeRule.onNodeWithText("Port").performTextInput("8080")
        composeRule.onNodeWithText("Connect").performClick()

        assertEquals("192.168.1.50", connectedHost)
        assertEquals("8080", connectedPort)
    }

    @Test
    fun tappingConnectWithBlankHostDoesNotInvokeCallback() {
        var invoked = false
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = null, onConnect = { _, _ -> invoked = true })
            }
        }
        // Host starts blank; tapping Connect must be a no-op.
        composeRule.onNodeWithText("Connect").performClick()
        assertEquals(false, invoked)
    }

    @Test
    fun connectingDisablesConnectActionAndShowsAffordance() {
        var invoked = false
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(
                    error = null,
                    connecting = true,
                    onConnect = { _, _ -> invoked = true },
                )
            }
        }
        composeRule.onNodeWithText("Host").performTextInput("192.0.2.1")
        // While connecting the action is blocked even with valid input.
        composeRule.onNodeWithText("Connecting…").performClick()
        assertEquals(false, invoked)
    }

    @Test
    fun errorStateRendersVisibleMessage() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = "Could not reach server", onConnect = { _, _ -> })
            }
        }
        composeRule.onNodeWithText("Could not reach server").assertExists()
    }

    @Test
    fun rendersEyebrowHeaderInsideTheme() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = null, onConnect = { _, _ -> })
            }
        }
        // Eyebrow uppercases its text.
        composeRule.onNodeWithText("STEP 1 — CONNECT").assertExists()
    }
}

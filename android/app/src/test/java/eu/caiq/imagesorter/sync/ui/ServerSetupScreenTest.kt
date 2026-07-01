package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun rendersAddressInputAndConnectButton() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = null, onConnect = {})
            }
        }
        composeRule.onNodeWithText("Server address").assertExists()
        composeRule.onNodeWithText("Connect").assertExists()
    }

    @Test
    fun tappingConnectInvokesCallbackWithEnteredAddress() {
        var connectedAddress: String? = null
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(
                    error = null,
                    onConnect = { a -> connectedAddress = a },
                )
            }
        }
        composeRule.onNodeWithText("Server address").performTextInput("https://media.example.com")
        composeRule.onNodeWithText("Connect").performClick()

        assertEquals("https://media.example.com", connectedAddress)
    }

    @Test
    fun tappingConnectWithBlankAddressDoesNotInvokeCallback() {
        var invoked = false
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = null, onConnect = { invoked = true })
            }
        }
        // Address starts blank; tapping Connect must be a no-op.
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
                    onConnect = { invoked = true },
                )
            }
        }
        composeRule.onNodeWithText("Server address").performTextInput("192.0.2.1:7000")
        // While connecting the action is blocked even with valid input.
        composeRule.onNodeWithText("Connecting…").performClick()
        assertEquals(false, invoked)
    }

    @Test
    fun errorStateRendersVisibleMessage() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = "Could not reach server", onConnect = {})
            }
        }
        composeRule.onNodeWithText("Could not reach server").assertExists()
    }

    @Test
    fun rendersEyebrowHeaderInsideTheme() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ServerSetupScreen(error = null, onConnect = {})
            }
        }
        // Eyebrow uppercases its text.
        composeRule.onNodeWithText("STEP 1 — CONNECT").assertExists()
    }
}

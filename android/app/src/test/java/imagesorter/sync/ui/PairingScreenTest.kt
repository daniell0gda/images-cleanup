package imagesorter.sync.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import imagesorter.sync.pairing.PairingState
import imagesorter.sync.ui.screens.PairingScreen
import imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingRendersSixCodeDigitsAndWaitingIndicator() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PairingScreen(state = PairingState.Pending("482913"))
            }
        }
        // Each of the six distinct digit characters is rendered in its own cell.
        listOf("4", "8", "2", "9", "1", "3").forEach { digit ->
            composeRule.onAllNodesWithText(digit).assertCountEquals(1)
        }
        composeRule.onNodeWithText("Waiting for approval…").assertExists()
    }

    @Test
    fun trustedRendersPairedAndNoWaitingIndicator() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PairingScreen(state = PairingState.Trusted)
            }
        }
        composeRule.onNodeWithText("Paired").assertExists()
        composeRule.onAllNodesWithText("Waiting for approval…").assertCountEquals(0)
    }

    @Test
    fun revokedRendersRePairPrompt() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PairingScreen(state = PairingState.Revoked)
            }
        }
        composeRule.onNodeWithText("Re-pair", substring = true).assertExists()
    }
}

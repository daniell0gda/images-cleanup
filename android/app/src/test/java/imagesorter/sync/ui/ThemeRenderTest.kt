package imagesorter.sync.ui

import androidx.compose.ui.test.junit4.createComposeRule
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
class ThemeRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightThemeComposesWithoutThrowing() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PairingScreen(state = PairingState.Pending("123456"))
            }
        }
        composeRule.onNodeWithText("Waiting for approval…").assertExists()
    }

    @Test
    fun darkThemeComposesWithoutThrowing() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = true) {
                PairingScreen(state = PairingState.Pending("123456"))
            }
        }
        composeRule.onNodeWithText("Waiting for approval…").assertExists()
    }
}

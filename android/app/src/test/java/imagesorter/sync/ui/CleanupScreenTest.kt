package imagesorter.sync.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import imagesorter.sync.ui.screens.CleanupPhase
import imagesorter.sync.ui.screens.CleanupScreen
import imagesorter.sync.ui.theme.ImageSorterSyncTheme
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
class CleanupScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleShowsStartActionAndTapInvokesCallback() {
        var started = 0
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                CleanupScreen(
                    phase = CleanupPhase.IDLE,
                    verifiedPresentCount = 0,
                    onStartCleanup = { started++ },
                    onRemoveSynced = {},
                )
            }
        }
        composeRule.onNodeWithText("Start cleanup").assertExists()
        composeRule.onNodeWithText("Start cleanup").performClick()
        assertEquals(1, started)
    }

    @Test
    fun verifyingShowsProgressAndNoRemoveAction() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                CleanupScreen(
                    phase = CleanupPhase.VERIFYING,
                    verifiedPresentCount = 0,
                    onStartCleanup = {},
                    onRemoveSynced = {},
                )
            }
        }
        composeRule.onNodeWithText("Verifying…").assertExists()
        composeRule.onAllNodesWithText("Remove", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Start cleanup").assertCountEquals(0)
    }

    @Test
    fun readyWithCountRendersCountAndTapInvokesRemove() {
        var removed = 0
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                CleanupScreen(
                    phase = CleanupPhase.READY_TO_REMOVE,
                    verifiedPresentCount = 42,
                    onStartCleanup = {},
                    onRemoveSynced = { removed++ },
                )
            }
        }
        composeRule.onNodeWithText("42").assertExists()
        composeRule.onNodeWithText("Remove", substring = true).performClick()
        assertEquals(1, removed)
    }

    @Test
    fun readyWithZeroCountDisablesRemoveAndTapDoesNothing() {
        var removed = 0
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                CleanupScreen(
                    phase = CleanupPhase.READY_TO_REMOVE,
                    verifiedPresentCount = 0,
                    onStartCleanup = {},
                    onRemoveSynced = { removed++ },
                )
            }
        }
        composeRule.onNodeWithText("Nothing to remove").assertIsNotEnabled()
        composeRule.onNodeWithText("Nothing to remove").performClick()
        assertEquals(0, removed)
    }
}

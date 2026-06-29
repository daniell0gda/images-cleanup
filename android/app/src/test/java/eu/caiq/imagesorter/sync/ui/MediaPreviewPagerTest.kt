package eu.caiq.imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.ui.components.MediaPreviewPager
import eu.caiq.imagesorter.sync.ui.components.PREVIEW_TAG
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared pager is generalized: the actions slot is empty by default (read-only
 * Images path shows no buttons) and present when a caller supplies one (not-people
 * path). Asserts node presence, not gesture pixels (§9 Notes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaPreviewPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultActionsSlotShowsNoButtons() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MediaPreviewPager(
                    items = listOf("a", "b"),
                    startIndex = 0,
                    onClose = {},
                ) { Text("img-$it") }
            }
        }
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Sync anyway").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun suppliedActionsSlotRendersWithCurrentIndex() {
        val clicks = mutableListOf<Int>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MediaPreviewPager(
                    items = listOf("a", "b"),
                    startIndex = 0,
                    onClose = {},
                    actions = { index ->
                        androidx.compose.material3.Button(onClick = { clicks.add(index) }) {
                            Text("Sync anyway")
                        }
                    },
                ) { Text("img-$it") }
            }
        }
        composeRule.onNodeWithText("Sync anyway").assertIsDisplayed()
        composeRule.onNodeWithText("Sync anyway").performClick()
        assertEquals(listOf(0), clicks)
    }

    @Test
    fun nonZoomablePageStillRendersPreview() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MediaPreviewPager(
                    items = listOf("video"),
                    startIndex = 0,
                    onClose = {},
                    zoomable = { false },
                ) { Text("video-$it") }
            }
        }
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("video-video").assertIsDisplayed()
    }
}

package eu.caiq.imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.ui.components.MediaPreviewPager
import eu.caiq.imagesorter.sync.ui.components.PREVIEW_TAG
import eu.caiq.imagesorter.sync.ui.components.previewCanPan
import eu.caiq.imagesorter.sync.ui.components.previewIndexAfterDelete
import eu.caiq.imagesorter.sync.ui.components.previewPagingEnabled
import eu.caiq.imagesorter.sync.ui.components.previewRotationDegrees
import org.junit.Assert.assertNull
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun pagingEnabledAtRestAndDisabledWhenZoomed() {
        // At 1:1 the pager owns the horizontal swipe so it can page between items.
        assertTrue(previewPagingEnabled(1f))
        // Once a page is zoomed the swipe pans within the page instead of paging.
        assertFalse(previewPagingEnabled(1.5f))
        assertFalse(previewPagingEnabled(5f))
    }

    @Test
    fun panOnlyStartsWhenZoomedSoSwipeFallsThroughToPager() {
        // At 1:1 the transformable must not claim a single-finger drag — it has to
        // fall through to the pager, otherwise swiping never advances the page.
        assertFalse(previewCanPan(1f))
        // Once zoomed in, the drag pans the zoomed page.
        assertTrue(previewCanPan(1.5f))
    }

    @Test
    fun onlyLandscapeImageInPortraitViewportRotates() {
        // Landscape content in a portrait viewport turns 90° to fill the screen.
        assertEquals(90f, previewRotationDegrees(isRotatableLandscape = true, viewportPortrait = true))
        // A landscape viewport already fits landscape content — no rotation.
        assertEquals(0f, previewRotationDegrees(isRotatableLandscape = true, viewportPortrait = false))
        // Portrait/square images and non-image (video) pages never rotate.
        assertEquals(0f, previewRotationDegrees(isRotatableLandscape = false, viewportPortrait = true))
        assertEquals(0f, previewRotationDegrees(isRotatableLandscape = false, viewportPortrait = false))
    }

    @Test
    fun deletingFromAMultiItemListKeepsPreviewOpenAtAValidIndex() {
        // Delete the middle item of a 3-item list while previewing index 1: the
        // preview should stay open clamped to a valid remaining index.
        val next = previewIndexAfterDelete(remainingCount = 2, deletedIndex = 1)
        assertEquals(1, next)
    }

    @Test
    fun deletingTheLastItemClosesThePreviewWithoutThrowing() {
        // Single-item list: removing it leaves nothing, so the preview closes (null)
        // rather than settling on an out-of-range page.
        assertNull(previewIndexAfterDelete(remainingCount = 0, deletedIndex = 0))
    }

    @Test
    fun deletingTheLastIndexClampsIntoTheNewBounds() {
        // Previewing the final item (index 2) of a 3-item list and deleting it:
        // the new list has 2 items, so the index must clamp to 1, not 2 (IOOBE).
        assertEquals(1, previewIndexAfterDelete(remainingCount = 2, deletedIndex = 2))
    }

    @Test
    fun deletingAtAnOutOfRangeIndexDoesNotThrow() {
        // A stale/over-range index (pager settled past the end) must not crash; it
        // clamps into the remaining list.
        assertEquals(2, previewIndexAfterDelete(remainingCount = 3, deletedIndex = 9))
        assertNull(previewIndexAfterDelete(remainingCount = 0, deletedIndex = 9))
    }

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
    fun rotatedLandscapePageStillRendersAndKeepsGestureGates() {
        // A page flagged landscape takes the rotation path; it must still render its
        // content, and the paging/pan gates stay identical to an unrotated page so
        // pinch-zoom, pan and page-swipe keep working.
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MediaPreviewPager(
                    items = listOf("a", "b"),
                    startIndex = 0,
                    onClose = {},
                    landscape = { true },
                ) { Text("img-$it") }
            }
        }
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("img-a").assertIsDisplayed()
        // Rotation does not change the gesture gates: page at rest, pan only when zoomed.
        assertTrue(previewPagingEnabled(1f))
        assertFalse(previewPagingEnabled(1.5f))
        assertTrue(previewCanPan(1.5f))
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

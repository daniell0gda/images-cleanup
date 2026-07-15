package imagesorter.sync.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import imagesorter.sync.ui.screens.LATEST_CHIP_TAG
import imagesorter.sync.ui.screens.LatestChip
import imagesorter.sync.ui.screens.handleLatestTap
import imagesorter.sync.ui.screens.shouldShowLatestChip
import imagesorter.sync.ui.theme.ImageSorterSyncTheme
import kotlinx.coroutines.test.runTest
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
 * Behaviour of the "↑ Latest" chip: when it is shown (scroll position) and what a
 * tap does depending on whether a date seek is active. Kept free of Compose so the
 * decision logic is unit-tested directly; the chip itself is a thin AnimatedVisibility
 * wrapper over these decisions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatestChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chipHiddenAtTopOfGridWhenNotSeeking() {
        assertFalse(shouldShowLatestChip(firstVisibleItemIndex = 0, isSeekActive = false))
    }

    @Test
    fun chipShownWhenScrolledPastFirstItem() {
        assertTrue(shouldShowLatestChip(firstVisibleItemIndex = 1, isSeekActive = false))
        assertTrue(shouldShowLatestChip(firstVisibleItemIndex = 42, isSeekActive = false))
    }

    @Test
    fun chipShownWhileSeekActiveEvenAtTop() {
        // After a seek the user is anchored at index 0 but still needs a way back.
        assertTrue(shouldShowLatestChip(firstVisibleItemIndex = 0, isSeekActive = true))
    }

    @Test
    fun chipHiddenWhilePreviewOpenRegardlessOfScrollOrSeek() {
        // The fullscreen viewer covers the grid, so the Latest chip must not show
        // through it — even when scrolled past the top or with a seek/segment active.
        assertFalse(shouldShowLatestChip(firstVisibleItemIndex = 42, isSeekActive = false, previewOpen = true))
        assertFalse(shouldShowLatestChip(firstVisibleItemIndex = 0, isSeekActive = true, previewOpen = true))
    }

    @Test
    fun closingPreviewRestoresPreviousVisibilityRules() {
        // With the preview closed the ordinary rules apply again.
        assertTrue(shouldShowLatestChip(firstVisibleItemIndex = 1, isSeekActive = false, previewOpen = false))
        assertFalse(shouldShowLatestChip(firstVisibleItemIndex = 0, isSeekActive = false, previewOpen = false))
    }

    @Test
    fun chipShownWheneverSegmentModeIsActive() {
        // Segment mode is a non-timeline view: the flag PhotosScreen passes here is
        // `segment != null`, so the Latest chip is always visible while a segment is shown.
        val segmentActive = true
        assertTrue(shouldShowLatestChip(firstVisibleItemIndex = 0, isSeekActive = segmentActive))
    }

    @Test
    fun tapWhileSegmentActiveExitsSegmentThenScrolls() = runTest {
        var segmentCleared = false
        val calls = mutableListOf<String>()
        handleLatestTap(
            isSeekActive = true, // segment active
            resetToLatest = { segmentCleared = true; calls.add("exitSegment") },
            scrollToTop = { calls.add("scroll") },
        )
        // Exiting segment mode (back to the newest-first timeline) precedes the scroll to top.
        assertTrue(segmentCleared)
        assertEquals(listOf("exitSegment", "scroll"), calls)
    }

    @Test
    fun tapWithActiveSeekResetsThenScrolls() = runTest {
        val calls = mutableListOf<String>()
        handleLatestTap(
            isSeekActive = true,
            resetToLatest = { calls.add("reset") },
            scrollToTop = { calls.add("scroll") },
        )
        // Reset (pager invalidation) must precede the scroll to the new top.
        assertEquals(listOf("reset", "scroll"), calls)
    }

    @Test
    fun tapWithoutSeekOnlyScrolls() = runTest {
        val calls = mutableListOf<String>()
        handleLatestTap(
            isSeekActive = false,
            resetToLatest = { calls.add("reset") },
            scrollToTop = { calls.add("scroll") },
        )
        // No seek active → no pager invalidation, just scroll to top.
        assertEquals(listOf("scroll"), calls)
    }

    @Test
    fun chipFadesInAndOutAsVisibilityToggles() {
        var visible by mutableStateOf(false)
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                LatestChip(visible = visible, onClick = {})
            }
        }
        // Hidden initially (AnimatedVisibility with no content composed).
        composeRule.onNodeWithTag(LATEST_CHIP_TAG).assertDoesNotExist()

        visible = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LATEST_CHIP_TAG).assertExists()

        visible = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LATEST_CHIP_TAG).assertDoesNotExist()
    }
}

package eu.caiq.imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import eu.caiq.imagesorter.sync.data.media.ChunkedDataSource
import eu.caiq.imagesorter.sync.ui.components.LocalMediaPreviewPageActive
import eu.caiq.imagesorter.sync.ui.components.MediaPreviewPager
import eu.caiq.imagesorter.sync.ui.components.PREVIEW_TAG
import eu.caiq.imagesorter.sync.ui.components.previewCanPan
import eu.caiq.imagesorter.sync.ui.components.previewIndexAfterDelete
import eu.caiq.imagesorter.sync.ui.components.previewPagingEnabled
import eu.caiq.imagesorter.sync.ui.screens.chunkedBearerDataSourceFactory
import eu.caiq.imagesorter.sync.ui.screens.latchVideoReady
import eu.caiq.imagesorter.sync.ui.screens.quietBufferingMode
import eu.caiq.imagesorter.sync.ui.screens.videoPosterVisible
import eu.caiq.imagesorter.sync.ui.screens.videoShowsPlaying
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPreviewPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun videoPageDataSourceIsChunkedAndBearerAuthed() {
        // The video page reads its stream through the chunked, bearer-authed data
        // source from the video-loading policy, not one open-ended request.
        val dataSource = chunkedBearerDataSourceFactory("tok-1").createDataSource()
        assertTrue(dataSource is ChunkedDataSource)
    }

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
    fun posterAndSpinnerShowDuringInitialBufferingBeforeFirstReady() {
        // Before the first frame is ready the page is still buffering, so the thumb
        // poster + spinner are shown, exactly as today.
        val everReady = latchVideoReady(everReady = false, playbackState = Player.STATE_BUFFERING)
        assertTrue(videoPosterVisible(everReady))
    }

    @Test
    fun posterStaysHiddenThroughAMidPlaybackStallOnceReady() {
        // Once ready, readiness latches on, so a later buffering stall does not
        // re-show the poster or the loading spinner.
        val afterReady = latchVideoReady(everReady = false, playbackState = Player.STATE_READY)
        val afterStall = latchVideoReady(afterReady, playbackState = Player.STATE_BUFFERING)
        assertFalse(videoPosterVisible(afterStall))
    }

    @Test
    fun aRebufferKeepsThePlayPauseControlShowingPlaying() {
        // A mid-playback buffering stall never toggles the play/pause control to
        // paused: it tracks play intent, not the buffering state.
        assertTrue(videoShowsPlaying(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun playerViewShowsNoBuiltInBufferingIndicator() {
        // No built-in buffering spinner during a stall — playback resumes quietly.
        assertEquals(PlayerView.SHOW_BUFFERING_NEVER, quietBufferingMode())
    }

    @Test
    fun onlyTheSettledPageReportsItselfAsTheVisiblePage() {
        // The pager tells its image slot which page is visible via
        // LocalMediaPreviewPageActive; a video page keys autoplay off it. The page the
        // pager settles on must be the only one flagged active, so a neighbour the
        // pager pre-composes mid-swipe preloads without autoplaying.
        val activeByItem = mutableMapOf<String, Boolean>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MediaPreviewPager(
                    items = listOf("a", "b", "c"),
                    startIndex = 1,
                    onClose = {},
                ) { item ->
                    activeByItem[item] = LocalMediaPreviewPageActive.current
                    Text("img-$item")
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(true, activeByItem["b"])
        activeByItem.filterKeys { it != "b" }.values.forEach { assertFalse(it) }
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

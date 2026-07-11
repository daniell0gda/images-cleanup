package eu.caiq.imagesorter.sync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.media.MediaListItem
import eu.caiq.imagesorter.sync.ui.screens.PHOTOS_CELL_TAG
import eu.caiq.imagesorter.sync.ui.screens.PHOTOS_TAG
import eu.caiq.imagesorter.sync.ui.screens.PHOTOS_VIDEO_BADGE_DESC
import eu.caiq.imagesorter.sync.ui.screens.PhotosGrid
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the gallery grid with a fixed [MediaListItem] list (a fake paging slice):
 * asserts header text, media cells, the video play badge, and that tapping a media
 * cell reports its zero-based media index (§9 Notes — node assertions, not pixels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun img(id: Long, day: String) =
        MediaEntity(id = id, kind = "image", dateTaken = "${day}T10:00:00", orderKey = id)

    private fun vid(id: Long, day: String) =
        MediaEntity(id = id, kind = "video", dateTaken = "${day}T10:00:00", orderKey = id)

    private val items = listOf(
        MediaListItem.Header("2024-03-03"),
        MediaListItem.Media(img(3, "2024-03-03")),
        MediaListItem.Media(vid(2, "2024-03-03")),
        MediaListItem.Header("2024-03-01"),
        MediaListItem.Media(img(1, "2024-03-01")),
    )

    @Test
    fun rendersHeadersCellsAndVideoBadge() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosGrid(items = items, onOpen = {}, cell = { _, m -> Box(m) })
            }
        }
        composeRule.onNodeWithText("2024-03-03").assertIsDisplayed()
        composeRule.onNodeWithText("2024-03-01").assertIsDisplayed()
        composeRule.onAllNodesWithTag(PHOTOS_CELL_TAG).assertCountEquals(3)
        // Exactly one of the three cells is a video.
        composeRule.onAllNodesWithContentDescription(PHOTOS_VIDEO_BADGE_DESC).assertCountEquals(1)
    }

    @Test
    fun tappingFirstCellReportsMediaIndexZero() {
        val opened = mutableListOf<Int>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosGrid(items = items, onOpen = { opened.add(it) }, cell = { _, m -> Box(m) })
            }
        }
        composeRule.onAllNodesWithTag(PHOTOS_CELL_TAG).onFirst().performClick()
        // First media cell is image id=3, the 0th media item (headers excluded).
        assertEquals(listOf(0), opened)
    }

    @Test
    fun reportsAccessForEveryRenderedRowSoPagingCanPrefetch() {
        val accessed = mutableSetOf<Int>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosGrid(items = items, onOpen = {}, onAccess = { accessed.add(it) }, cell = { _, m -> Box(m) })
            }
        }
        composeRule.waitForIdle()
        // Every flat row (headers + cells) reports its index; the live screen forwards this to
        // LazyPagingItems.get(index) so Paging prefetches the next page instead of stalling.
        assertEquals((items.indices).toSet(), accessed)
    }

    @Test
    fun gridRootCarriesPhotosTag() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosGrid(items = items, onOpen = {}, cell = { _, m -> Box(m) })
            }
        }
        composeRule.onAllNodesWithTag(PHOTOS_TAG).onFirst().assertIsDisplayed()
    }
}

package eu.caiq.imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.ui.screens.PhotosPreview
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the gallery preview overlay (the [PhotosPreview] slot PhotosScreen opens on
 * tap) with a fixed media list: asserts it supplies a Delete action whose click runs
 * the handler with the current page index and never throws (§9 Notes — node
 * assertions, not the device MediaStore delete dialog).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun img(id: Long) =
        MediaEntity(id = id, kind = "image", dateTaken = "2024-03-03T10:00:00", orderKey = id)

    @Test
    fun previewSuppliesADeleteActionThatRunsWithoutThrowing() {
        val deleted = mutableListOf<Int>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosPreview(
                    items = listOf(img(1), img(2), img(3)),
                    startIndex = 0,
                    onClose = {},
                    onDelete = { deleted.add(it) },
                ) { Text("img-${it.id}") }
            }
        }
        composeRule.onNodeWithContentDescription("Delete").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf(0), deleted)
    }

    @Test
    fun deletingTheOnlyItemRunsTheHandlerAndDoesNotThrow() {
        val deleted = mutableListOf<Int>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosPreview(
                    items = listOf(img(1)),
                    startIndex = 0,
                    onClose = {},
                    onDelete = { deleted.add(it) },
                ) { Text("img-${it.id}") }
            }
        }
        composeRule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf(0), deleted)
    }

    @Test
    fun whileDeletingTheTrashButtonIsReplacedByASpinner() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                PhotosPreview(
                    items = listOf(img(1), img(2)),
                    startIndex = 0,
                    deleting = true,
                    onClose = {},
                    onDelete = {},
                ) { Text("img-${it.id}") }
            }
        }
        // The trash button is gone while the delete is in flight (spinner in its place).
        composeRule.onNodeWithContentDescription("Delete").assertDoesNotExist()
    }
}

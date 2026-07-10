package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.data.api.AlbumApi
import eu.caiq.imagesorter.sync.data.api.dto.AlbumDto
import eu.caiq.imagesorter.sync.data.api.dto.AlbumItemsBody
import eu.caiq.imagesorter.sync.data.api.dto.AlbumNameBody
import eu.caiq.imagesorter.sync.data.api.dto.CreateAlbumBody
import eu.caiq.imagesorter.sync.data.api.dto.DeletedDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.api.dto.ShareDto
import eu.caiq.imagesorter.sync.data.media.AlbumRepository
import eu.caiq.imagesorter.sync.data.media.MediaUrls
import eu.caiq.imagesorter.sync.ui.screens.ALBUM_DELETE_TAG
import eu.caiq.imagesorter.sync.ui.screens.ALBUM_DETAIL_TAG
import eu.caiq.imagesorter.sync.ui.screens.AlbumsScreenContent
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A fake album API returning a fixed album list + items so detail rendering can be driven. */
private class DetailAlbumApi(private val album: AlbumDto) : AlbumApi {
    /** Records ids passed to [delete] so a test can assert the delete call fired. */
    val deletedIds = mutableListOf<Long>()

    override suspend fun albums(): List<AlbumDto> = listOf(album)
    override suspend fun create(body: CreateAlbumBody): AlbumDto = album
    override suspend fun rename(id: Long, body: AlbumNameBody): AlbumDto = album
    override suspend fun delete(id: Long): DeletedDto { deletedIds.add(id); return DeletedDto(true) }
    override suspend fun addItems(id: Long, body: AlbumItemsBody): AlbumDto = album
    override suspend fun removeItems(id: Long, body: AlbumItemsBody): AlbumDto = album
    override suspend fun items(id: Long): List<MediaItemDto> =
        listOf(MediaItemDto(1, "image", "2024-03-10T00:00:00", 100, 80))
    override suspend fun share(id: Long): ShareDto = ShareDto("t", "https://x/share/t")
    override suspend fun revoke(id: Long) {}
}

/**
 * Verifies the "Go to album" landing: driving [AlbumsScreenContent] with an [openAlbumId]
 * resolves that album from the loaded list and actually renders the album DETAIL view (not
 * the tile list), and consumes the one-shot request so it does not re-open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumDetailNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openAlbumIdRendersTheAlbumDetailViewAndConsumesTheRequest() {
        val album = AlbumDto(7, "Trip", null, "2024-01-01T00:00:00", 1, 1, false, null)
        val repo = AlbumRepository(DetailAlbumApi(album))
        var consumed = false

        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                AlbumsScreenContent(
                    repo = repo,
                    urls = MediaUrls("https://x"),
                    token = null,
                    openAlbumId = 7,
                    onAlbumConsumed = { consumed = true },
                )
            }
        }

        composeRule.onNodeWithTag(ALBUM_DETAIL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue("openAlbumId must be consumed once", consumed) }
    }

    @Test
    fun deleteAlbumConfirmationCallsDeleteAndReturnsToTheList() {
        val album = AlbumDto(7, "Trip", null, "2024-01-01T00:00:00", 1, 1, false, null)
        val api = DetailAlbumApi(album)
        val repo = AlbumRepository(api)

        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                AlbumsScreenContent(
                    repo = repo,
                    urls = MediaUrls("https://x"),
                    token = null,
                    openAlbumId = 7,
                    onAlbumConsumed = {},
                )
            }
        }

        composeRule.onNodeWithTag(ALBUM_DETAIL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ALBUM_DELETE_TAG).performClick()
        // Confirm in the dialog (the destructive action, not the header icon).
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf(7L), api.deletedIds) }
        composeRule.onNodeWithTag(ALBUM_DETAIL_TAG).assertDoesNotExist()
    }
}

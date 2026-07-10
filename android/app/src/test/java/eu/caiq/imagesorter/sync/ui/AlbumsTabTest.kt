package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eu.caiq.imagesorter.sync.data.api.AlbumApi
import eu.caiq.imagesorter.sync.data.api.dto.AlbumDto
import eu.caiq.imagesorter.sync.data.api.dto.AlbumItemsBody
import eu.caiq.imagesorter.sync.data.api.dto.AlbumNameBody
import eu.caiq.imagesorter.sync.data.api.dto.CreateAlbumBody
import eu.caiq.imagesorter.sync.data.api.dto.DeletedDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.api.dto.RevokedDto
import eu.caiq.imagesorter.sync.data.api.dto.ShareDto
import eu.caiq.imagesorter.sync.data.media.AlbumRepository
import eu.caiq.imagesorter.sync.ui.screens.AlbumTile
import eu.caiq.imagesorter.sync.ui.screens.albumItemsToEntities
import eu.caiq.imagesorter.sync.ui.screens.openAlbumFor
import eu.caiq.imagesorter.sync.ui.screens.removeFromAlbum
import eu.caiq.imagesorter.sync.ui.screens.shareAlbumLink
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records membership calls so Remove-from-album can be asserted to hit the right endpoint. */
private class RecordingAlbumApi : AlbumApi {
    var lastRemovedId: Long? = null
    var lastRemovedBody: AlbumItemsBody? = null
    var lastSharedId: Long? = null
    var itemsResponse: List<MediaItemDto> = emptyList()
    val updatedEntry = AlbumDto(7, "Trip", "Pixel", "2024-01-01T00:00:00", 2, 9, false, null)

    override suspend fun albums(): List<AlbumDto> = emptyList()
    override suspend fun create(body: CreateAlbumBody): AlbumDto = updatedEntry
    override suspend fun rename(id: Long, body: AlbumNameBody): AlbumDto = updatedEntry
    override suspend fun delete(id: Long): DeletedDto = DeletedDto(true)
    override suspend fun addItems(id: Long, body: AlbumItemsBody): AlbumDto = updatedEntry
    override suspend fun removeItems(id: Long, body: AlbumItemsBody): AlbumDto {
        lastRemovedId = id
        lastRemovedBody = body
        return updatedEntry
    }
    override suspend fun items(id: Long): List<MediaItemDto> = itemsResponse
    override suspend fun share(id: Long): ShareDto {
        lastSharedId = id
        return ShareDto("t", "https://photos.example.com/share/t")
    }
    override suspend fun revoke(id: Long): RevokedDto = RevokedDto(true)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumsTabTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeShellHasPhotosAlbumsSyncTabsInOrder() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                HomeShell(
                    selectedTab = HomeTab.ALBUMS,
                    onTabSelected = {},
                    photos = {},
                    albums = {},
                    sync = {},
                )
            }
        }
        composeRule.onNodeWithText("Photos").assertIsDisplayed()
        composeRule.onNodeWithText("Albums").assertIsDisplayed()
        composeRule.onNodeWithText("Sync").assertIsDisplayed()
    }

    @Test
    fun albumTileShowsNameCountAndCreatedBy() {
        val album = AlbumDto(1, "Holiday", "Pixel 8", "2024-01-01T00:00:00", 12, 5, false, null)
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                AlbumTile(album = album, coverModel = null, onOpen = {})
            }
        }
        composeRule.onNodeWithText("Holiday").assertIsDisplayed()
        composeRule.onNodeWithText("12 items").assertIsDisplayed()
        composeRule.onNodeWithText("Pixel 8").assertIsDisplayed()
    }

    @Test
    fun albumItemsAreMappedToEntitiesPreservingNewestFirstOrder() {
        val items = listOf(
            MediaItemDto(30, "image", "2024-03-10T00:00:00", 100, 80),
            MediaItemDto(20, "video", "2024-03-09T00:00:00", 200, 100),
        )
        val entities = albumItemsToEntities(items)
        assertEquals(listOf(30L, 20L), entities.map { it.id })
        assertEquals("video", entities[1].kind)
    }

    @Test
    fun openAlbumForResolvesSelectedIdToItsAlbumAndNullKeepsTheList() {
        val albums = listOf(
            AlbumDto(1, "Trip", null, "2024-01-01T00:00:00", 3, null, false, null),
            AlbumDto(2, "Beach", null, "2024-01-02T00:00:00", 5, null, false, null),
        )
        // A "go to album" selection opens that album's detail...
        assertEquals(2L, openAlbumFor(2, albums)?.id)
        // ...an unknown id (album not yet loaded) and no selection both keep the list.
        assertEquals(null, openAlbumFor(99, albums))
        assertEquals(null, openAlbumFor(null, albums))
    }

    @Test
    fun removeFromAlbumCallsMembershipDeleteEndpointOnly() = runTest {
        val api = RecordingAlbumApi()
        val repo = AlbumRepository(api)
        var systemDeleteInvoked = false

        removeFromAlbum(
            albumId = 7,
            mediaIds = listOf(20, 30),
            repo = repo,
            onSystemDelete = { systemDeleteInvoked = true },
        )

        assertEquals(7L, api.lastRemovedId)
        assertEquals(listOf(20L, 30L), api.lastRemovedBody?.mediaIds)
        assertFalse("Remove-from-album must never trigger the system delete dialog", systemDeleteInvoked)
    }

    @Test
    fun shareAlbumLinkSharesAlbumAndCopiesServerUrlVerbatim() = runTest {
        val api = RecordingAlbumApi()
        val repo = AlbumRepository(api)
        var copied: String? = null
        var confirmed: String? = null

        val url = shareAlbumLink(
            albumId = 7,
            repo = repo,
            copyToClipboard = { copied = it },
            confirm = { confirmed = it },
        )

        assertEquals(7L, api.lastSharedId)
        // The app must surface the server-built URL verbatim (§3.11), never construct it.
        assertEquals("https://photos.example.com/share/t", url)
        assertEquals("https://photos.example.com/share/t", copied)
        assertEquals("https://photos.example.com/share/t", confirmed)
    }
}

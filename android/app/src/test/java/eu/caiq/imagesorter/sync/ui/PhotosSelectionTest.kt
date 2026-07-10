package eu.caiq.imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import eu.caiq.imagesorter.sync.ui.screens.AlbumSelectionAction
import eu.caiq.imagesorter.sync.ui.screens.SelectionActionsBar
import eu.caiq.imagesorter.sync.ui.screens.defaultAlbumName
import eu.caiq.imagesorter.sync.ui.screens.runAlbumNameAction
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Records calls and returns canned responses so the action logic can be asserted. */
private class FakeAlbumApi : AlbumApi {
    var entryResponse: AlbumDto = AlbumDto(42, "x", null, "2024-01-01T00:00:00", 3, null, false, null)
    var shareResponse: ShareDto = ShareDto("tok", "https://photos.example.com/share/tok")

    var lastCreate: CreateAlbumBody? = null
    var lastSharedId: Long? = null

    override suspend fun albums(): List<AlbumDto> = emptyList()
    override suspend fun create(body: CreateAlbumBody): AlbumDto { lastCreate = body; return entryResponse }
    override suspend fun rename(id: Long, body: AlbumNameBody): AlbumDto = entryResponse
    override suspend fun delete(id: Long): DeletedDto = DeletedDto(true)
    override suspend fun addItems(id: Long, body: AlbumItemsBody): AlbumDto = entryResponse
    override suspend fun removeItems(id: Long, body: AlbumItemsBody): AlbumDto = entryResponse
    override suspend fun items(id: Long): List<MediaItemDto> = emptyList()
    override suspend fun share(id: Long): ShareDto { lastSharedId = id; return shareResponse }
    override suspend fun revoke(id: Long): RevokedDto = RevokedDto(true)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosSelectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionBarOffersCreateAlbumAddToAlbumAndCreateLink() {
        val actions = mutableListOf<AlbumSelectionAction>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                SelectionActionsBar(count = 2, onAction = { actions.add(it) }, onClose = {})
            }
        }
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Create album").assertIsDisplayed()
        composeRule.onNodeWithText("Add to album").assertIsDisplayed()
        composeRule.onNodeWithText("Create link").assertIsDisplayed()

        composeRule.onNodeWithText("Create link").performClick()
        assertEquals(listOf(AlbumSelectionAction.CreateLink), actions)
    }

    @Test
    fun defaultNameIsAlbumWithTodaysDate() {
        assertEquals("Album 2024-03-02", defaultAlbumName(LocalDate.of(2024, 3, 2)))
    }

    @Test
    fun createAlbumCallsCreateAndDoesNotShareOrCopy() = runTest {
        val api = FakeAlbumApi()
        val repo = AlbumRepository(api)
        val copied = mutableListOf<String>()
        var confirmed: String? = null

        runAlbumNameAction(
            action = AlbumSelectionAction.CreateAlbum,
            name = "Album 2024-03-02",
            mediaIds = listOf(1, 2, 3),
            createdBy = "Pixel",
            repo = repo,
            copyToClipboard = { copied.add(it) },
            confirm = { confirmed = it },
        )

        assertEquals("Album 2024-03-02", api.lastCreate?.name)
        assertEquals(listOf(1L, 2L, 3L), api.lastCreate?.mediaIds)
        assertEquals("Pixel", api.lastCreate?.createdBy)
        assertNull(api.lastSharedId)
        assertTrue(copied.isEmpty())
        assertNull(confirmed)
    }

    @Test
    fun createAlbumInvokesOnCreatedWithTheNewAlbumAndDoesNotFireLinkConfirm() = runTest {
        val api = FakeAlbumApi().apply {
            entryResponse = AlbumDto(99, "Trip", null, "2024-01-01T00:00:00", 3, null, false, null)
        }
        val repo = AlbumRepository(api)
        var created: AlbumDto? = null
        var confirmed: String? = null

        runAlbumNameAction(
            action = AlbumSelectionAction.CreateAlbum,
            name = "Trip",
            mediaIds = listOf(1, 2, 3),
            createdBy = "Pixel",
            repo = repo,
            copyToClipboard = {},
            confirm = { confirmed = it },
            onCreated = { created = it },
        )

        assertEquals(99L, created?.id)
        assertNull("Create album must not fire the link-copied confirm", confirmed)
    }

    @Test
    fun createLinkDoesNotInvokeOnCreated() = runTest {
        val api = FakeAlbumApi()
        val repo = AlbumRepository(api)
        var created: AlbumDto? = null

        runAlbumNameAction(
            action = AlbumSelectionAction.CreateLink,
            name = "Trip",
            mediaIds = listOf(9),
            createdBy = "Pixel",
            repo = repo,
            copyToClipboard = {},
            confirm = {},
            onCreated = { created = it },
        )

        assertNull("Create link must not fire the album-created signal", created)
    }

    @Test
    fun createLinkCreatesThenSharesAndCopiesVerbatimShareUrl() = runTest {
        val serverUrl = "https://photos.example.com/share/Xq7zZ-token"
        val api = FakeAlbumApi().apply { shareResponse = ShareDto("Xq7zZ-token", serverUrl) }
        val repo = AlbumRepository(api)
        val copied = mutableListOf<String>()
        var confirmed: String? = null

        runAlbumNameAction(
            action = AlbumSelectionAction.CreateLink,
            name = "Album 2024-03-02",
            mediaIds = listOf(9),
            createdBy = "Pixel",
            repo = repo,
            copyToClipboard = { copied.add(it) },
            confirm = { confirmed = it },
        )

        assertEquals("Album 2024-03-02", api.lastCreate?.name)
        assertEquals(42L, api.lastSharedId)
        assertEquals(listOf(serverUrl), copied)
        assertEquals(serverUrl, confirmed)
    }

    @Test
    fun albumCreatedSnackbarOffersGoToAlbumWhichNavigatesToTheNewAlbum() {
        var goneTo: Long? = null
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                val host = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    eu.caiq.imagesorter.sync.ui.screens.confirmAlbumCreated(
                        host = host,
                        albumId = 77,
                        onGoToAlbum = { goneTo = it },
                        // Isolate the action path from the auto-dismiss timeout: the bar must
                        // stay up long enough for the tap. The timeout is covered separately.
                        timeoutMillis = Long.MAX_VALUE,
                    )
                }
                androidx.compose.material3.SnackbarHost(hostState = host)
            }
        }

        composeRule.onNodeWithText("Go to album").assertIsDisplayed()
        composeRule.onNodeWithText("Go to album").performClick()
        composeRule.runOnIdle { assertEquals(77L, goneTo) }
    }

    @Test
    fun albumCreatedBarAutoDismissesAfterTimeoutWithoutNavigating() = runTest {
        // With no host consuming it, an Indefinite snackbar would suspend forever; the 5s cap
        // must end the wait and return without invoking the "Go to album" navigation. Removing
        // the timeout would leave this coroutine (and the bar) hanging indefinitely.
        val host = androidx.compose.material3.SnackbarHostState()
        var goneTo: Long? = null

        eu.caiq.imagesorter.sync.ui.screens.confirmAlbumCreated(
            host = host,
            albumId = 77,
            onGoToAlbum = { goneTo = it },
            timeoutMillis = 5_000L,
        )

        assertNull(goneTo)
    }

    @Test
    fun goToDateFabLiftsAboveTheAlbumCreatedBarWhenItIsVisible() {
        assertTrue(
            "FAB must sit higher when the album-created bar is visible",
            eu.caiq.imagesorter.sync.ui.screens.fabBottomPadding(true) >
                eu.caiq.imagesorter.sync.ui.screens.fabBottomPadding(false),
        )
    }

    @Test
    fun nameDialogPrefillsDefaultAndConfirmsTrimmedName() {
        var confirmed: String? = null
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                eu.caiq.imagesorter.sync.ui.screens.AlbumNameDialog(
                    defaultName = "Album 2024-03-02",
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Album 2024-03-02").assertIsDisplayed()
        composeRule.onNodeWithText("Create").performClick()
        assertEquals("Album 2024-03-02", confirmed)
    }
}

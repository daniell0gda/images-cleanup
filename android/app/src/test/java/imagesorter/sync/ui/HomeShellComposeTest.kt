package imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import imagesorter.sync.sync.SyncPhase
import imagesorter.sync.sync.SyncProgress
import imagesorter.sync.ui.screens.PHOTOS_TAG
import imagesorter.sync.ui.screens.PhotosScreen
import imagesorter.sync.ui.screens.shouldShowSyncSpinner
import imagesorter.sync.ui.theme.ImageSorterSyncTheme
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
 * Drives the post-pairing home shell directly: it renders a Photos | Sync bottom
 * bar, defaults to Photos, and switches tabs on tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShellComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeShellShowsBothTabsAndOpensOnPhotos() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                HomeShell(
                    selectedTab = HomeTab.PHOTOS,
                    onTabSelected = {},
                    photos = { PhotosScreen() },
                    albums = {},
                    sync = { Text("sync-content") },
                )
            }
        }
        composeRule.onNodeWithText("Photos").assertIsDisplayed()
        composeRule.onNodeWithText("Sync").assertIsDisplayed()
        // Defaults to the Photos tab content.
        composeRule.onNodeWithTag(PHOTOS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("sync-content").assertDoesNotExist()
    }

    @Test
    fun tappingSyncSelectsSyncTab() {
        val selections = mutableListOf<HomeTab>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                HomeShell(
                    selectedTab = HomeTab.PHOTOS,
                    onTabSelected = { selections.add(it) },
                    photos = { PhotosScreen() },
                    albums = {},
                    sync = { Text("sync-content") },
                )
            }
        }
        composeRule.onNodeWithText("Sync").performClick()
        assertEquals(listOf(HomeTab.SYNC), selections)
    }

    @Test
    fun syncTabRendersItsContent() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                HomeShell(
                    selectedTab = HomeTab.SYNC,
                    onTabSelected = {},
                    photos = { PhotosScreen() },
                    albums = {},
                    sync = { Text("sync-content") },
                )
            }
        }
        composeRule.onNodeWithText("sync-content").assertIsDisplayed()
        composeRule.onNodeWithTag(PHOTOS_TAG).assertDoesNotExist()
    }

    @Test
    fun shouldShowSyncSpinnerTrueWhileUploading() {
        assertTrue(shouldShowSyncSpinner(SyncProgress(phase = SyncPhase.UPLOADING)))
    }

    @Test
    fun shouldShowSyncSpinnerFalseWhenIdle() {
        assertFalse(shouldShowSyncSpinner(SyncProgress(phase = SyncPhase.IDLE)))
    }

    @Test
    fun shouldShowSyncSpinnerFalseWhenFinished() {
        assertFalse(shouldShowSyncSpinner(SyncProgress(phase = SyncPhase.DONE)))
        assertFalse(shouldShowSyncSpinner(SyncProgress(phase = SyncPhase.ERROR)))
    }

    @Test
    fun syncTabSpinnerVisibleFromPhotosTabWhenSyncActive() {
        // The indicator must be visible even when another tab (Photos) is selected,
        // so a running sync is noticeable from anywhere in the home shell.
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                HomeShell(
                    selectedTab = HomeTab.PHOTOS,
                    onTabSelected = {},
                    syncActive = true,
                    photos = { Text("photos") },
                    albums = { Text("albums") },
                    sync = { Text("sync-content") },
                )
            }
        }
        composeRule.onNodeWithTag(SYNC_TAB_SPINNER_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun syncTabSpinnerAbsentWhenSyncInactive() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                HomeShell(
                    selectedTab = HomeTab.PHOTOS,
                    onTabSelected = {},
                    syncActive = false,
                    photos = { Text("photos") },
                    albums = { Text("albums") },
                    sync = { Text("sync-content") },
                )
            }
        }
        composeRule.onNodeWithTag(SYNC_TAB_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}

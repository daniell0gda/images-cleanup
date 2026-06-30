package eu.caiq.imagesorter.sync.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.ui.screens.PHOTOS_TAG
import eu.caiq.imagesorter.sync.ui.screens.PhotosScreen
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
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
}

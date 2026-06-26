package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.ui.screens.STATUS_TILE_TAG
import eu.caiq.imagesorter.sync.ui.screens.MainStatusScreen
import eu.caiq.imagesorter.sync.ui.screens.StatusFilter
import eu.caiq.imagesorter.sync.ui.screens.StatusRow
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainStatusScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rows = listOf(
        StatusRow(name = "a.jpg", status = SyncStatus.SYNCED),
        StatusRow(name = "b.jpg", status = SyncStatus.PENDING),
        StatusRow(name = "c.jpg", status = SyncStatus.IN_PROGRESS),
    )

    @Test
    fun rendersOneTilePerRow() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).assertCountEquals(rows.size)
    }

    @Test
    fun filterChipsRenderAndTappingNonSelectedInvokesCallback() {
        val changes = mutableListOf<StatusFilter>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.WORKING_SET,
                    onFilterChange = { changes.add(it) },
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onNodeWithText("Working set").assertExists()
        composeRule.onNodeWithText("Synced today").assertExists()
        composeRule.onNodeWithText("All").assertExists()

        composeRule.onNodeWithText("Synced today").performClick()
        composeRule.onNodeWithText("All").performClick()
        assertEquals(listOf(StatusFilter.SYNCED_TODAY, StatusFilter.ALL), changes)
    }

    @Test
    fun primaryActionsInvokeTheirCallbacks() {
        var syncNow = 0
        var cleanup = 0
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = { syncNow++ },
                    onCleanup = { cleanup++ },
                )
            }
        }
        composeRule.onNodeWithText("Back up now").performClick()
        composeRule.onNodeWithText("Free up space").performClick()
        assertEquals(1, syncNow)
        assertEquals(1, cleanup)
    }

    @Test
    fun emptyRowsShowsEmptyStateAndNoTiles() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = emptyList(),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onNodeWithText("All caught up").assertIsDisplayed()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).assertCountEquals(0)
    }

    @Test
    fun failedRowSurfacesFailedCountInHeader() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(
                        StatusRow(name = "ok.jpg", status = SyncStatus.SYNCED),
                        StatusRow(name = "bad.jpg", status = SyncStatus.FAILED, failureReason = "timeout"),
                    ),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onNodeWithText(" failed").assertExists()
    }

    @Test
    fun eachStatusBadgeIsDistinguishableInSemanticsTree() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(
                        StatusRow(name = "synced.jpg", status = SyncStatus.SYNCED),
                        StatusRow(name = "uploading.jpg", status = SyncStatus.IN_PROGRESS),
                        StatusRow(name = "failed.jpg", status = SyncStatus.FAILED),
                        StatusRow(name = "pending.jpg", status = SyncStatus.PENDING),
                    ),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Synced").assertExists()
        composeRule.onNodeWithContentDescription("Uploading").assertExists()
        composeRule.onNodeWithContentDescription("Failed").assertExists()
        composeRule.onNodeWithContentDescription("Pending").assertExists()
    }

    @Test
    fun selectedFilterChipExposesSelectedSemantics() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.SYNCED_TODAY,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onNodeWithText("Synced today").assertIsSelected()
        composeRule.onNodeWithText("Working set").assertIsNotSelected()
        composeRule.onNodeWithText("All").assertIsNotSelected()
    }
}

package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import eu.caiq.imagesorter.sync.domain.model.FailureReason
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.sync.SyncPhase
import eu.caiq.imagesorter.sync.sync.SyncProgress
import eu.caiq.imagesorter.sync.ui.screens.FAILED_CHIP_TAG
import eu.caiq.imagesorter.sync.ui.screens.FailureDetail
import eu.caiq.imagesorter.sync.ui.screens.STATUS_TILE_TAG
import eu.caiq.imagesorter.sync.ui.screens.MainStatusScreen
import eu.caiq.imagesorter.sync.ui.screens.StatusFilter
import eu.caiq.imagesorter.sync.ui.screens.StatusRow
import eu.caiq.imagesorter.sync.ui.screens.StatusTotals
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
    fun tappingFailedCountOpensModalListingEachFailureWithReason() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(StatusRow(name = "broken.jpg", status = SyncStatus.FAILED)),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    totals = StatusTotals(safe = 0, todo = 0, failed = 1),
                    failures = listOf(
                        FailureDetail(name = "broken.jpg", reason = FailureReason.UNREADABLE, retryable = false),
                    ),
                )
            }
        }
        // Modal is closed until the failed count is tapped: the filename only
        // appears inside the modal (tiles render no name), so it must be absent now.
        composeRule.onNodeWithText("broken.jpg").assertDoesNotExist()

        composeRule.onNodeWithTag(FAILED_CHIP_TAG).performClick()

        composeRule.onNodeWithText("broken.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("Unreadable file").assertIsDisplayed()
    }

    @Test
    fun failureModalDismissesOnClose() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(StatusRow(name = "broken.jpg", status = SyncStatus.FAILED)),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    totals = StatusTotals(safe = 0, todo = 0, failed = 1),
                    failures = listOf(
                        FailureDetail(name = "broken.jpg", reason = FailureReason.SIZE_MISMATCH, retryable = true),
                    ),
                )
            }
        }
        composeRule.onNodeWithTag(FAILED_CHIP_TAG).performClick()
        composeRule.onNodeWithText("broken.jpg").assertIsDisplayed()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("broken.jpg").assertDoesNotExist()
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
    fun duplicateDisplayNamesRenderWithoutKeyCollision() {
        // Pixel motion photos share a display name; two such items in the same
        // status used to collide on the grid key and crash. Distinct keys fix it.
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(
                        StatusRow(name = "PXL.MP.jpg", status = SyncStatus.IN_PROGRESS, key = "p:1"),
                        StatusRow(name = "PXL.MP.jpg", status = SyncStatus.IN_PROGRESS, key = "p:2"),
                    ),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).assertCountEquals(2)
    }

    @Test
    fun activeSyncShowsProgressLabelAndDisablesBackUpButton() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    syncProgress = SyncProgress(
                        phase = SyncPhase.UPLOADING,
                        totalFiles = 10,
                        completedFiles = 3,
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Uploading 3/10…").assertExists()
        composeRule.onNodeWithText("Backing up…").assertIsNotEnabled()
        composeRule.onNodeWithText("Back up now").assertDoesNotExist()
    }

    @Test
    fun reportingPhaseExplainsServerSideSorting() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    syncProgress = SyncProgress(phase = SyncPhase.REPORTING),
                )
            }
        }
        composeRule.onNodeWithText("Sorting on the server…").assertExists()
    }

    @Test
    fun safeCountComesFromGlobalTotalsNotTheFilteredRows() {
        // Working-set filter hides synced items, so the visible rows have none —
        // yet the "photos safe" headline must still reflect the library-wide total.
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(StatusRow(name = "pending.jpg", status = SyncStatus.PENDING)),
                    selectedFilter = StatusFilter.WORKING_SET,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    totals = StatusTotals(safe = 42, todo = 1, failed = 0),
                )
            }
        }
        composeRule.onNodeWithText("42").assertExists()
        composeRule.onNodeWithText("photos safe\non the server").assertExists()
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

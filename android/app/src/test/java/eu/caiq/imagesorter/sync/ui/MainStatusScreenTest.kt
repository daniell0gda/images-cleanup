package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import eu.caiq.imagesorter.sync.ui.components.PREVIEW_TAG
import eu.caiq.imagesorter.sync.ui.components.SELECTION_BAR_TAG
import eu.caiq.imagesorter.sync.ui.components.SELECTION_CHECK_DESC
import eu.caiq.imagesorter.sync.domain.model.FailureReason
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.sync.SyncPhase
import eu.caiq.imagesorter.sync.sync.SyncProgress
import eu.caiq.imagesorter.sync.ui.screens.FAILED_CHIP_TAG
import eu.caiq.imagesorter.sync.ui.screens.FailureDetail
import eu.caiq.imagesorter.sync.ui.screens.LOADING_STATE_TAG
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
    fun emptyRowsWhileDiscoveringShowsLoadingNotEmptyState() {
        // An empty working set during the initial device scan must read as "loading",
        // not the misleading "All caught up" (which looks like the scan already finished).
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = emptyList(),
                    isDiscovering = true,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        composeRule.onNodeWithTag(LOADING_STATE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("All caught up").assertDoesNotExist()
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
    fun unclassifiedTileRendersDistinctNotPeopleBadge() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = listOf(
                        StatusRow(name = "synced.jpg", status = SyncStatus.SYNCED),
                        StatusRow(name = "notpeople.jpg", status = SyncStatus.UNCLASSIFIED),
                    ),
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
        // The Not People badge carries its own content description, distinct from the
        // synced/pending/failed/in-progress badges.
        composeRule.onNodeWithContentDescription("Not people").assertExists()
        composeRule.onNodeWithContentDescription("Synced").assertExists()
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
    fun syncNotConfiguredErrorRendersDistinctMessageNotGenericServerError() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    syncProgress = SyncProgress(
                        phase = SyncPhase.ERROR,
                        message = "Sync isn't set up on the server yet.",
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Sync isn't set up on the server yet.").assertExists()
        composeRule.onNodeWithText("Server error 503").assertDoesNotExist()
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
    fun notPeopleFilterPillRendersAndInvokesCallback() {
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
        composeRule.onNodeWithText("Not people").assertExists()
        // The filter row scrolls horizontally, so bring the pill on-screen before tapping.
        composeRule.onNodeWithText("Not people").performScrollTo().performClick()
        assertEquals(listOf(StatusFilter.NOT_PEOPLE), changes)
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

    private val notPeopleRows = listOf(
        StatusRow(name = "np1.jpg", status = SyncStatus.UNCLASSIFIED, key = "k1"),
        StatusRow(name = "np2.jpg", status = SyncStatus.UNCLASSIFIED, key = "k2"),
        StatusRow(name = "np3.jpg", status = SyncStatus.UNCLASSIFIED, key = "k3"),
    )

    private fun setNotPeopleScreen() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = notPeopleRows,
                    selectedFilter = StatusFilter.NOT_PEOPLE,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                )
            }
        }
    }

    @Test
    fun notPeopleTapOpensFullscreenPreview() {
        setNotPeopleScreen()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun notPeopleLongPressEntersSelectionWithThatTileSelected() {
        setNotPeopleScreen()
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst()
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertIsDisplayed()
        // The long-pressed tile is selected (exactly one check overlay shown).
        composeRule.onAllNodesWithContentDescription(SELECTION_CHECK_DESC).assertCountEquals(1)
    }

    private fun setSyncScreen(
        filter: StatusFilter,
        onOverride: (List<StatusRow>) -> Unit = {},
        onDelete: (List<StatusRow>) -> Unit = {},
    ) {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = rows,
                    selectedFilter = filter,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    onOverride = onOverride,
                    onDelete = onDelete,
                )
            }
        }
    }

    @Test
    fun syncTabWorkingSetTileTapOpensPreview() {
        setSyncScreen(StatusFilter.WORKING_SET)
        composeRule.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun syncTabSyncedTodayTileTapOpensPreview() {
        setSyncScreen(StatusFilter.SYNCED_TODAY)
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun syncTabAllTileTapOpensPreview() {
        setSyncScreen(StatusFilter.ALL)
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun syncTabTileTapDoesNotEnterSelectionMode() {
        // A non-Not-People filter has no multi-select: a long-press does nothing.
        setSyncScreen(StatusFilter.ALL)
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst()
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun syncTabPreviewDeleteInvokesOnDeleteWithCurrentRow() {
        val deleted = mutableListOf<List<StatusRow>>()
        setSyncScreen(StatusFilter.ALL, onDelete = { deleted.add(it) })
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG)[1].performClick()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(rows[1]), deleted.single())
    }

    @Test
    fun syncTabPreviewDeleteKeepsPreviewOpen() {
        // Delete must not close the preview: the system delete dialog should appear
        // over it. Here onDelete doesn't mutate rows, so the preview simply stays.
        setSyncScreen(StatusFilter.ALL)
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun syncTabPreviewStaysThenClosesAsDeletedRowsLeave() {
        // Mirror the real flow: onDelete removes the row from the live list. The
        // preview stays open (sliding to the next item) until nothing remains, then
        // closes on its own.
        val live = androidx.compose.runtime.mutableStateListOf(rows[0], rows[1])
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = live,
                    selectedFilter = StatusFilter.ALL,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    onDelete = { toDelete -> live.removeAll(toDelete) },
                )
            }
        }
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        // One item still remains after the first delete → preview stays open.
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        // Deleting the last item closes the preview.
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun syncTabPreviewCloseReturnsToGrid() {
        setSyncScreen(StatusFilter.ALL)
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).assertCountEquals(rows.size)
    }

    @Test
    fun notPeoplePreviewActionsInvokeCallbacks() {
        val overridden = mutableListOf<List<StatusRow>>()
        val deleted = mutableListOf<List<StatusRow>>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                MainStatusScreen(
                    rows = notPeopleRows,
                    selectedFilter = StatusFilter.NOT_PEOPLE,
                    onFilterChange = {},
                    onSyncNow = {},
                    onCleanup = {},
                    onOverride = { overridden.add(it) },
                    onDelete = { deleted.add(it) },
                )
            }
        }
        // "Sync anyway" dismisses the preview (no confirmation dialog follows).
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithText("Sync anyway").performClick()
        assertEquals(listOf(notPeopleRows[0]), overridden.single())
        composeRule.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()

        // Delete invokes the callback but keeps the preview open for the system dialog.
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(notPeopleRows[0]), deleted.single())
        composeRule.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun selectionTapTogglesSelectionAndShowsCount() {
        setNotPeopleScreen()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst()
            .performTouchInput { longClick() }
        // One selected after long-press.
        composeRule.onNodeWithText("1 selected").assertExists()

        // Tapping a second tile selects it too.
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG)[1].performClick()
        composeRule.onNodeWithText("2 selected").assertExists()

        // Tapping the first again deselects it.
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG)[0].performClick()
        composeRule.onNodeWithText("1 selected").assertExists()
    }

    @Test
    fun selectAllSelectsEveryTile() {
        setNotPeopleScreen()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst()
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("3 selected").assertExists()
        composeRule.onAllNodesWithContentDescription(SELECTION_CHECK_DESC)
            .assertCountEquals(notPeopleRows.size)
    }

    @Test
    fun deselectingLastTileExitsSelectionMode() {
        setNotPeopleScreen()
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst()
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertIsDisplayed()
        // Deselect the only selected tile → selection mode exits.
        composeRule.onAllNodesWithTag(STATUS_TILE_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertDoesNotExist()
    }
}

package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.ui.screens.DATE_PICKER_CANCEL_TAG
import eu.caiq.imagesorter.sync.ui.screens.DATE_PICKER_CONFIRM_TAG
import eu.caiq.imagesorter.sync.ui.screens.DATE_PICKER_FAB_TAG
import eu.caiq.imagesorter.sync.ui.screens.DATE_PICKER_LOADING_TAG
import eu.caiq.imagesorter.sync.ui.screens.DatePickerFab
import eu.caiq.imagesorter.sync.ui.screens.DatePickerSheet
import eu.caiq.imagesorter.sync.ui.screens.DatePickerState
import eu.caiq.imagesorter.sync.ui.screens.shouldShowGoToDateFab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-level checks for the Go-To-Date picker UI: the calendar FAB and the modal
 * it opens. Node/tag assertions only (§9 Notes — no gesture-pixel or sheet-animation
 * timing assumptions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DatePickerModalTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun goToDateFabHiddenWhilePreviewOpen() {
        // Criterion: the FAB is not shown while the fullscreen preview overlay is open.
        assertFalse(shouldShowGoToDateFab(inSelectionMode = false, previewOpen = true))
    }

    @Test
    fun goToDateFabShownWhenPreviewClosedAndNotSelecting() {
        // Criterion: closing the preview restores the FAB (no selection active).
        assertTrue(shouldShowGoToDateFab(inSelectionMode = false, previewOpen = false))
    }

    @Test
    fun goToDateFabHiddenWhileSelecting() {
        // Criterion: the existing multi-select gate still hides the FAB.
        assertFalse(shouldShowGoToDateFab(inSelectionMode = true, previewOpen = false))
    }

    @Test
    fun calendarFabIsVisibleAndReportsTaps() {
        var taps = 0
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                DatePickerFab(onClick = { taps++ })
            }
        }
        composeRule.onNodeWithTag(DATE_PICKER_FAB_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DATE_PICKER_FAB_TAG).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun sheetShowsLoadingIndicatorWhileDatesAreNull() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                DatePickerSheet(
                    state = null,
                    onSelect = {},
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithTag(DATE_PICKER_LOADING_TAG).assertIsDisplayed()
        // No tiles or confirm action exist until the dates arrive.
        composeRule.onNodeWithTag(DATE_PICKER_CONFIRM_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelDismissesWithoutTriggeringASeek() {
        var cancelled = false
        var confirmed = false
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                DatePickerSheet(
                    state = DatePickerState(mapOf("2024" to mapOf("03" to listOf(1)))).selectYear("2024"),
                    onSelect = {},
                    onConfirm = { confirmed = true },
                    onCancel = { cancelled = true },
                )
            }
        }
        composeRule.onNodeWithTag(DATE_PICKER_CANCEL_TAG).performClick()
        assertTrue(cancelled)
        // Cancel must never seek.
        assertEquals(false, confirmed)
    }
}

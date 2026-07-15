package imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import imagesorter.sync.data.api.dto.MediaDatesDto

/** Test tag on the Go-To-Date FAB. */
const val DATE_PICKER_FAB_TAG = "datePickerFab"

/** Test tag on the loading indicator shown while the dates tree is being fetched. */
const val DATE_PICKER_LOADING_TAG = "datePickerLoading"

/** Test tag on the Confirm action. */
const val DATE_PICKER_CONFIRM_TAG = "datePickerConfirm"

/** Test tag on the Cancel action. */
const val DATE_PICKER_CANCEL_TAG = "datePickerCancel"

/** Test tag on each selectable date tile (year / month / day). */
const val DATE_PICKER_TILE_TAG = "datePickerTile"

/**
 * Whether the Go-To-Date FAB should be shown. It is hidden while multi-select is active
 * (it shares the bottom edge with the selection action bar) and while the fullscreen
 * preview overlay is open ([previewOpen]) so it does not show through the overlay.
 */
fun shouldShowGoToDateFab(inSelectionMode: Boolean, previewOpen: Boolean): Boolean =
    !inSelectionMode && !previewOpen

/** Bottom-end FAB that opens the Go-To-Date picker modal. */
@Composable
fun DatePickerFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(onClick = onClick, modifier = modifier.testTag(DATE_PICKER_FAB_TAG)) {
        Icon(Icons.Default.CalendarMonth, contentDescription = "Go to date")
    }
}

/**
 * Stateful Go-To-Date picker modal bottom sheet. Loads the available-dates tree via
 * [loadDates] on open (spinner until it arrives), then drives [DatePickerSheet] with a
 * local [DatePickerState]: tiles drill down, the breadcrumb jumps back, the back
 * gesture goes up one level (dismissing at the top). Confirm reports the resolved
 * [DateSegment] (date + drilled-to granularity) to [onConfirm] and dismisses; Cancel and
 * outside-dismiss call [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerModal(
    loadDates: suspend () -> MediaDatesDto,
    onConfirm: (DateSegment) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var dates by remember { mutableStateOf<MediaDatesDto?>(null) }
    var state by remember { mutableStateOf<DatePickerState?>(null) }

    LaunchedEffect(Unit) {
        val loaded = loadDates()
        dates = loaded
        state = DatePickerState(loaded)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val current = state
        if (current != null) {
            // Within the modal, back goes up one level; at the year level it dismisses.
            BackHandler {
                val up = current.up()
                if (up == null) onDismiss() else state = up
            }
        }
        DatePickerSheet(
            state = current,
            onSelect = { label -> current?.let { state = it.selectDeeper(label) } },
            onConfirm = { current?.confirmedSegment()?.let(onConfirm) ?: onDismiss() },
            onCancel = onDismiss,
            onJump = { level -> current?.let { state = it.goTo(level) } },
        )
    }
}

/**
 * Stateless content of the Go-To-Date picker bottom sheet. A null [state] means the
 * available-dates tree is still loading (shows a spinner); once present it renders the
 * breadcrumb, the tile grid for the current level, and Confirm/Cancel. [onSelect]
 * fires the tapped tile label (year/month/day), [onConfirm] accepts at the current
 * granularity, [onCancel] dismisses without seeking.
 */
@Composable
fun DatePickerSheet(
    state: DatePickerState?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onJump: (DatePickerLevel) -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        if (state == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag(DATE_PICKER_LOADING_TAG))
            }
            return@Surface
        }

        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
            Breadcrumb(state, onJump = onJump)
            DateTileGrid(
                tiles = state.tiles,
                selected = state.day,
                onSelect = onSelect,
                modifier = Modifier.heightIn(max = 320.dp).padding(vertical = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag(DATE_PICKER_CANCEL_TAG)) {
                    Text("Cancel")
                }
                OutlinedButton(onClick = onConfirm, modifier = Modifier.testTag(DATE_PICKER_CONFIRM_TAG)) {
                    Text("Confirm")
                }
            }
        }
    }
}

/** The tappable breadcrumb trail; a leading "All years" crumb returns to the year list. */
@Composable
private fun Breadcrumb(state: DatePickerState, onJump: (DatePickerLevel) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "All years",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onJump(DatePickerLevel.YEAR) },
        )
        state.breadcrumb.forEach { segment ->
            Text("  ›  ")
            Text(
                segment.label,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onJump(segment.level) },
            )
        }
    }
}

@Composable
private fun DateTileGrid(
    tiles: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), modifier = modifier) {
        items(tiles) { label ->
            val isSelected = label == selected
            Box(
                modifier = Modifier
                    .testTag(DATE_PICKER_TILE_TAG)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .clickable { onSelect(label) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

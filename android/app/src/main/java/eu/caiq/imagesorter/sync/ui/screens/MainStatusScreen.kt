package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.caiq.imagesorter.sync.domain.model.SyncStatus

/** Filter chips on the main status view (working set is the default). */
enum class StatusFilter { WORKING_SET, SYNCED_TODAY, ALL }

/** One read-only status row rendered in the list. */
data class StatusRow(
    val name: String,
    val status: SyncStatus,
    val failureReason: String? = null,
)

/**
 * PLACEHOLDER main status screen. Read-only list of media with status icons, a
 * filter-chip row, and the two primary actions (sync, clean up). The list is a
 * plain LazyColumn here; the production screen must be virtualized + paged to
 * stay fast over a 100k-item library.
 *
 * TODO(designer): paging, thumbnails/preview, row tap behaviour, final visuals.
 */
@Composable
fun MainStatusScreen(
    rows: List<StatusRow>,
    selectedFilter: StatusFilter,
    onFilterChange: (StatusFilter) -> Unit,
    onSyncNow: () -> Unit,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        FilterRow(selectedFilter, onFilterChange)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onSyncNow) { Text("Sync now") }
            OutlinedButton(onClick = onCleanup) { Text("Clean up") }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.name + it.status.name }) { row ->
                ListItem(
                    headlineContent = { Text(row.name) },
                    supportingContent = {
                        row.failureReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    },
                    leadingContent = { Icon(statusIcon(row.status), contentDescription = row.status.name) },
                )
            }
        }
    }
}

@Composable
private fun FilterRow(selected: StatusFilter, onChange: (StatusFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == StatusFilter.WORKING_SET,
            onClick = { onChange(StatusFilter.WORKING_SET) },
            label = { Text("Working set") },
        )
        FilterChip(
            selected = selected == StatusFilter.SYNCED_TODAY,
            onClick = { onChange(StatusFilter.SYNCED_TODAY) },
            label = { Text("Synced today") },
        )
        FilterChip(
            selected = selected == StatusFilter.ALL,
            onClick = { onChange(StatusFilter.ALL) },
            label = { Text("All") },
        )
    }
}

private fun statusIcon(status: SyncStatus): ImageVector = when (status) {
    SyncStatus.SYNCED -> Icons.Filled.CheckCircle
    SyncStatus.FAILED -> Icons.Filled.Error
    SyncStatus.IN_PROGRESS -> Icons.Filled.CloudUpload
    SyncStatus.PENDING -> Icons.Filled.Schedule
}

package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.sync.SyncPhase
import eu.caiq.imagesorter.sync.sync.SyncProgress
import eu.caiq.imagesorter.sync.ui.components.AppearOnEntry
import eu.caiq.imagesorter.sync.ui.components.Eyebrow
import eu.caiq.imagesorter.sync.ui.components.MediaThumbnail
import eu.caiq.imagesorter.sync.ui.components.clickableScale
import eu.caiq.imagesorter.sync.ui.components.statusColor
import eu.caiq.imagesorter.sync.ui.theme.MonoLabel
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/** Filter chips on the main status view (working set is the default). */
enum class StatusFilter { WORKING_SET, SYNCED_TODAY, ALL }

/** Test tag on each status tile, so the rendered tile count is assertable. */
const val STATUS_TILE_TAG = "statusTile"

/** Test tag on the header's clickable failed-count chip (opens the failures modal). */
const val FAILED_CHIP_TAG = "failedCountChip"

/**
 * One read-only status row rendered in the list. [key] is a stable, globally
 * unique grid key — display [name] is not unique (e.g. Pixel motion photos share
 * a filename), so it must not be used as the list key.
 */
data class StatusRow(
    val name: String,
    val status: SyncStatus,
    val failureReason: String? = null,
    val key: String = name,
    /** Local `MediaStore._ID` for loading the thumbnail; null falls back to a gradient. */
    val mediaStoreId: Long? = null,
    /** MIME type, used to pick the image vs video MediaStore collection. */
    val mimeType: String? = null,
)

/**
 * Library-wide counts for the header, computed from the full (unfiltered) cache so
 * they reflect real totals — not just whatever the active filter happens to show.
 */
data class StatusTotals(val safe: Int, val todo: Int, val failed: Int) {
    companion object {
        fun fromRows(rows: List<StatusRow>): StatusTotals = StatusTotals(
            safe = rows.count { it.status == SyncStatus.SYNCED },
            todo = rows.count { it.status == SyncStatus.PENDING || it.status == SyncStatus.IN_PROGRESS },
            failed = rows.count { it.status == SyncStatus.FAILED },
        )
    }
}

/**
 * Main status — the hero. A photo-tile grid keyed to each item's sync state, with
 * a count headline, filter pills, and the two primary actions. While a sync is in
 * flight, a mint "secure sweep" crosses the grid.
 *
 * Header counts come from [totals] (library-wide, filter-independent) so the
 * "photos safe" tally grows as files finish processing even while the working-set
 * filter hides the now-synced items.
 */
@Composable
fun MainStatusScreen(
    rows: List<StatusRow>,
    selectedFilter: StatusFilter,
    onFilterChange: (StatusFilter) -> Unit,
    onSyncNow: () -> Unit,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier,
    syncProgress: SyncProgress = SyncProgress(),
    totals: StatusTotals = StatusTotals.fromRows(rows),
    failures: List<FailureDetail> = emptyList(),
) {
    val c = VaultTheme.colors
    val safe = totals.safe
    val todo = totals.todo
    val failed = totals.failed
    var showFailures by remember { mutableStateOf(false) }
    // A run is active from discovery through reporting; placement to the server's
    // destination only happens in REPORTING, after the whole batch has uploaded.
    val activeSync = syncProgress.phase != SyncPhase.IDLE && !syncProgress.isFinished
    val phaseText = phaseLabel(syncProgress)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(horizontal = 22.dp)
            .padding(top = 24.dp, bottom = 18.dp),
    ) {
        Eyebrow("Backup status")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%,d".format(safe),
                style = MonoLabel.copy(fontSize = 44.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.5).sp),
                color = c.text,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "photos safe\non the server",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$todo", style = MonoLabel.copy(fontSize = 13.sp, fontWeight = FontWeight.W600), color = c.amber)
            Text(" still to back up", style = MonoLabel.copy(fontSize = 13.sp), color = c.muted)
            if (failed > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickableScale { showFailures = true }
                        .testTag(FAILED_CHIP_TAG)
                        .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    Text("·  $failed", style = MonoLabel.copy(fontSize = 13.sp, fontWeight = FontWeight.W600), color = c.coral)
                    Text(" failed", style = MonoLabel.copy(fontSize = 13.sp), color = c.muted)
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = "Show failed items",
                        tint = c.coral,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        AnimatedVisibility(activeSync && phaseText != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = c.accent,
                )
                Spacer(Modifier.size(8.dp))
                Text(phaseText ?: "", style = MonoLabel.copy(fontSize = 12.5.sp), color = c.accent)
            }
        }

        Spacer(Modifier.height(18.dp))
        FilterRow(selectedFilter, onFilterChange)
        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                EmptyState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                        AppearOnEntry(
                            delayMs = (index % 12) * 26,
                            key = row.key,
                            modifier = Modifier.animateItem(),
                        ) {
                            MediaTile(row)
                        }
                    }
                }
            }
            SweepOverlay(visible = activeSync)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSyncNow,
                enabled = !activeSync,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.onAccent,
                    disabledContainerColor = c.accent.copy(alpha = 0.55f),
                    disabledContentColor = c.onAccent,
                ),
            ) {
                if (activeSync) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = c.onAccent,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Backing up…", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Back up now", fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedButton(
                onClick = onCleanup,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Free up space") }
        }
    }

    if (showFailures) {
        FailureDialog(failures = failures, onDismiss = { showFailures = false })
    }
}

/**
 * Plain-language description of what the in-flight run is doing right now. Returns
 * null for idle/finished phases. The UPLOADING and REPORTING split matters: files
 * land in the destination only once REPORTING ("Sorting on the server") runs, after
 * the whole batch has uploaded — so the user knows uploading isn't the final step.
 */
private fun phaseLabel(progress: SyncProgress): String? = when (progress.phase) {
    SyncPhase.DISCOVERING -> "Looking for new photos…"
    SyncPhase.RECONCILING -> "Checking what's already backed up…"
    SyncPhase.UPLOADING -> "Uploading ${progress.completedFiles}/${progress.totalFiles}…"
    SyncPhase.REPORTING -> "Sorting on the server…"
    SyncPhase.IDLE, SyncPhase.DONE, SyncPhase.ERROR -> null
}

@Composable
private fun FilterRow(selected: StatusFilter, onChange: (StatusFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Working set", selected == StatusFilter.WORKING_SET) { onChange(StatusFilter.WORKING_SET) }
        Pill("Synced today", selected == StatusFilter.SYNCED_TODAY) { onChange(StatusFilter.SYNCED_TODAY) }
        Pill("All", selected == StatusFilter.ALL) { onChange(StatusFilter.ALL) }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val c = VaultTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickableScale(onClick = onClick)
            .semantics { selected = on }
            .background(if (on) c.accent else c.surface)
            .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MonoLabel.copy(fontSize = 12.5.sp), color = if (on) c.onAccent else c.muted)
    }
}

@Composable
private fun MediaTile(row: StatusRow, modifier: Modifier = Modifier) {
    val c = VaultTheme.colors
    val dim = row.status == SyncStatus.PENDING
    Box(modifier = modifier.testTag(STATUS_TILE_TAG).aspectRatio(1f).clip(RoundedCornerShape(13.dp))) {
        MediaThumbnail(
            mediaStoreId = row.mediaStoreId,
            mimeType = row.mimeType,
            fallbackSeed = row.name,
            modifier = Modifier.fillMaxSize(),
        )
        if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        StatusBadge(
            status = row.status,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }
}

@Composable
private fun StatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val c = VaultTheme.colors
    val tint = statusColor(status)
    val label = when (status) {
        SyncStatus.SYNCED -> "Synced"
        SyncStatus.IN_PROGRESS -> "Uploading"
        SyncStatus.FAILED -> "Failed"
        SyncStatus.PENDING -> "Pending"
    }
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (status == SyncStatus.SYNCED || status == SyncStatus.IN_PROGRESS || status == SyncStatus.FAILED) tint else Color.Black.copy(alpha = 0.45f))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SyncStatus.SYNCED -> Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
            SyncStatus.IN_PROGRESS -> Icon(Icons.Rounded.CloudUpload, null, tint = Color(0xFF2A1B02), modifier = Modifier.size(13.dp))
            SyncStatus.FAILED -> Icon(Icons.Rounded.PriorityHigh, null, tint = Color(0xFF2A0B07), modifier = Modifier.size(14.dp))
            SyncStatus.PENDING -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.muted))
        }
    }
}

@Composable
private fun SweepOverlay(visible: Boolean) {
    val c = VaultTheme.colors
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        val t by rememberInfiniteTransition(label = "sweep").animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "sweepX",
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.5f to c.accent.copy(alpha = 0.22f),
                        1.0f to Color.Transparent,
                        startX = t * 1000f - 300f,
                        endX = t * 1000f + 300f,
                    ),
                ),
        )
    }
}

@Composable
private fun EmptyState() {
    val c = VaultTheme.colors
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("All caught up", style = MaterialTheme.typography.titleMedium, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text("Nothing waiting to back up.", style = MonoLabel.copy(fontSize = 13.sp), color = c.muted)
    }
}

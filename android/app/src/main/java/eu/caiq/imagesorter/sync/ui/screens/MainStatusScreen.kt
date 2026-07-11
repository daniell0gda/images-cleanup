package eu.caiq.imagesorter.sync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import eu.caiq.imagesorter.sync.ui.components.MediaFullImage
import eu.caiq.imagesorter.sync.ui.components.MediaPreviewPager
import eu.caiq.imagesorter.sync.ui.components.MediaThumbnail
import eu.caiq.imagesorter.sync.ui.components.SelectableMediaGrid
import eu.caiq.imagesorter.sync.ui.components.SelectionTopBar
import eu.caiq.imagesorter.sync.ui.components.clickableScale
import eu.caiq.imagesorter.sync.ui.components.statusColor
import eu.caiq.imagesorter.sync.ui.theme.MonoLabel
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/** Filter chips on the main status view (working set is the default). */
enum class StatusFilter { WORKING_SET, SYNCED_TODAY, ALL, NOT_PEOPLE }

/** Test tag on each status tile, so the rendered tile count is assertable. */
const val STATUS_TILE_TAG = "statusTile"

/** Test tag on the preview's per-item "Sync Now" button (disabled while in flight). */
const val SYNC_NOW_TAG = "syncNowButton"

/** Test tag on the working-set loading state (initial device scan in progress). */
const val LOADING_STATE_TAG = "statusLoading"

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
 * Whether a sync run is active (from discovery through reporting), i.e. not idle
 * and not finished. Drives both the in-screen sweep/progress and the bottom-nav
 * Sync-tab spinner so both react to the same shared [SyncProgress].
 */
fun shouldShowSyncSpinner(progress: SyncProgress): Boolean =
    progress.phase != SyncPhase.IDLE && !progress.isFinished

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
    // True while the initial working-set scan runs; an empty list then reads as
    // "still scanning" rather than "nothing to back up".
    isDiscovering: Boolean = false,
    syncProgress: SyncProgress = SyncProgress(),
    totals: StatusTotals = StatusTotals.fromRows(rows),
    failures: List<FailureDetail> = emptyList(),
    // "Not people" review actions; defaulted to no-ops so the read-only callers and
    // the existing screen tests need not supply them.
    onOverride: (List<StatusRow>) -> Unit = {},
    onDelete: (List<StatusRow>) -> Unit = {},
    // Per-item "Sync Now" from the preview: force-place-uploads the current item.
    onSyncItem: (StatusRow) -> Unit = {},
    // MediaStore ids currently in flight via "Sync Now"; drives the button's
    // disabled/in-progress state until the engine reports the item's outcome.
    syncingIds: Set<Long> = emptySet(),
) {
    val c = VaultTheme.colors
    // Interactive state for the Not People review grid. Keyed on the active filter so
    // switching away from Not People discards any preview/selection in progress.
    var previewIndex by remember(selectedFilter) { mutableStateOf<Int?>(null) }
    var selectionMode by remember(selectedFilter) { mutableStateOf(false) }
    var selectedKeys by remember(selectedFilter) { mutableStateOf<Set<Any>>(emptySet()) }
    val notPeople = selectedFilter == StatusFilter.NOT_PEOPLE

    fun clearSelection() {
        selectedKeys = emptySet()
        selectionMode = false
    }

    fun toggle(row: StatusRow) {
        val next = if (row.key in selectedKeys) selectedKeys - row.key else selectedKeys + row.key
        selectedKeys = next
        // Deselecting the last tile leaves selection mode (Google-Photos behavior).
        selectionMode = next.isNotEmpty()
    }

    // Back exits multi-select instead of leaving the screen. (The fullscreen
    // preview handles its own back via MediaPreviewPager and wins while open.)
    BackHandler(enabled = selectionMode) { clearSelection() }

    val selectedRows = rows.filter { it.key in selectedKeys }
    val safe = totals.safe
    val todo = totals.todo
    val failed = totals.failed
    var showFailures by remember { mutableStateOf(false) }
    // A run is active from discovery through reporting; placement to the server's
    // destination only happens in REPORTING, after the whole batch has uploaded.
    val activeSync = shouldShowSyncSpinner(syncProgress)
    val phaseText = phaseLabel(syncProgress)

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
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

        // A run that ended in ERROR surfaces its message here (e.g. the distinct
        // "sync isn't set up" state for a session-open 503) instead of failing silently.
        AnimatedVisibility(syncProgress.phase == SyncPhase.ERROR && syncProgress.message != null) {
            Text(
                syncProgress.message ?: "",
                style = MonoLabel.copy(fontSize = 12.5.sp),
                color = c.coral,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        FilterRow(selectedFilter, onFilterChange)
        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            when {
                rows.isEmpty() && isDiscovering -> LoadingState()
                rows.isEmpty() -> EmptyState()
                notPeople -> Column {
                    AnimatedVisibility(selectionMode) {
                        Column {
                            SelectionTopBar(
                                count = selectedKeys.size,
                                onSelectAll = {
                                    selectedKeys = rows.map { it.key }.toSet()
                                    selectionMode = true
                                },
                                onOverride = {
                                    onOverride(selectedRows)
                                    clearSelection()
                                },
                                onDelete = {
                                    onDelete(selectedRows)
                                    clearSelection()
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    SelectableMediaGrid(
                        items = rows,
                        keyOf = { it.key },
                        selectedKeys = selectedKeys,
                        inSelectionMode = selectionMode,
                        onTap = { index -> previewIndex = index },
                        onToggle = { row -> toggle(row) },
                        onLongPress = { row ->
                            selectionMode = true
                            selectedKeys = setOf(row.key)
                        },
                        tileTag = STATUS_TILE_TAG,
                        modifier = Modifier.weight(1f),
                    ) { row -> MediaTileContent(row) }
                }
                else -> StatusGrid(rows, onTap = { index -> previewIndex = index })
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

        // Fullscreen preview overlay — opens with a scale+fade expand from the grid.
        // Shared by the Not People review and the read-only status filters; the action
        // row differs (Not People also offers "Sync anyway"), the delete plumbing is the same.
        //
        // Delete keeps the preview open so the system delete dialog appears over it (not
        // over the grid). As the confirmed row leaves [rows] the pager slides to the next
        // item; this effect closes the preview once the last item is gone.
        LaunchedEffect(rows.isEmpty()) {
            if (rows.isEmpty()) previewIndex = null
        }
        AnimatedVisibility(
            visible = previewIndex != null && rows.isNotEmpty(),
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(260)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160)),
        ) {
            val idx = previewIndex
            if (idx != null && rows.isNotEmpty()) {
                MediaPreviewPager(
                    items = rows,
                    startIndex = idx.coerceIn(0, rows.size - 1),
                    onClose = { previewIndex = null },
                    actions = { page ->
                        val c = VaultTheme.colors
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (notPeople) {
                                Button(
                                    onClick = {
                                        rows.getOrNull(page)?.let { onOverride(listOf(it)) }
                                        previewIndex = null
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                ) { Text("Sync anyway", fontWeight = FontWeight.SemiBold) }
                            }
                            val current = rows.getOrNull(page)
                            val notSynced = current?.status == SyncStatus.PENDING ||
                                current?.status == SyncStatus.IN_PROGRESS ||
                                current?.status == SyncStatus.FAILED
                            if (!notPeople && notSynced) {
                                val inFlight = syncingIds.contains(current?.mediaStoreId)
                                Button(
                                    // No dismiss: the preview stays open (like Delete) so the
                                    // user watches the item complete and leave the working set.
                                    onClick = { current?.let { onSyncItem(it) } },
                                    enabled = !inFlight,
                                    modifier = Modifier.weight(1f).testTag(SYNC_NOW_TAG),
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                ) {
                                    if (inFlight) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = c.onAccent,
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text("Syncing…", fontWeight = FontWeight.SemiBold)
                                    } else {
                                        Text("Sync Now", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            OutlinedButton(
                                // No dismiss here: the preview stays up while the system
                                // delete dialog shows, then follows [rows] as it shrinks.
                                onClick = { rows.getOrNull(page)?.let { onDelete(listOf(it)) } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Delete") }
                        }
                    },
                ) { row -> MediaFullImage(row.mediaStoreId, row.mimeType, row.name) }
            }
        }

        if (showFailures) {
            FailureDialog(failures = failures, onDismiss = { showFailures = false })
        }
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        Pill("Working set", selected == StatusFilter.WORKING_SET) { onChange(StatusFilter.WORKING_SET) }
        Pill("Synced today", selected == StatusFilter.SYNCED_TODAY) { onChange(StatusFilter.SYNCED_TODAY) }
        Pill("All", selected == StatusFilter.ALL) { onChange(StatusFilter.ALL) }
        Pill("Not people", selected == StatusFilter.NOT_PEOPLE) { onChange(StatusFilter.NOT_PEOPLE) }
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

/**
 * 3-column status grid used by every filter except Not People. Tapping a tile opens
 * the fullscreen preview at that item; there is no multi-select here (long-press is a
 * no-op) — that is exclusive to the Not People review grid.
 */
@Composable
private fun StatusGrid(rows: List<StatusRow>, onTap: (Int) -> Unit) {
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
                Box(
                    Modifier
                        .testTag(STATUS_TILE_TAG)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .clickableScale { onTap(index) },
                ) {
                    MediaTileContent(row)
                }
            }
        }
    }
}

/**
 * The visual content of a tile (thumbnail + dim + status badge), without the tile
 * tag or click handling — shared by the read-only grid and the selectable Not People
 * grid so both render identical tiles.
 */
@Composable
private fun MediaTileContent(row: StatusRow, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        MediaThumbnail(
            mediaStoreId = row.mediaStoreId,
            mimeType = row.mimeType,
            fallbackSeed = row.name,
            modifier = Modifier.fillMaxSize(),
        )
        if (row.status == SyncStatus.PENDING) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        }
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
        SyncStatus.UNCLASSIFIED -> "Not people"
    }
    // Filled badges (a colored disc with a glyph) for the decided states — synced,
    // uploading, failed and not-people; a small muted dot for the still-pending one.
    val filled = status == SyncStatus.SYNCED ||
        status == SyncStatus.IN_PROGRESS ||
        status == SyncStatus.FAILED ||
        status == SyncStatus.UNCLASSIFIED
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (filled) tint else Color.Black.copy(alpha = 0.45f))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SyncStatus.SYNCED -> Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
            SyncStatus.IN_PROGRESS -> Icon(Icons.Rounded.CloudUpload, null, tint = Color(0xFF2A1B02), modifier = Modifier.size(13.dp))
            SyncStatus.FAILED -> Icon(Icons.Rounded.PriorityHigh, null, tint = Color(0xFF2A0B07), modifier = Modifier.size(14.dp))
            SyncStatus.PENDING -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.muted))
            SyncStatus.UNCLASSIFIED -> Icon(Icons.Rounded.PersonOff, null, tint = Color.White, modifier = Modifier.size(13.dp))
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

/** Shown in place of [EmptyState] while the first device scan is still running. */
@Composable
private fun LoadingState() {
    val c = VaultTheme.colors
    Column(
        Modifier.fillMaxSize().testTag(LOADING_STATE_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = c.accent)
        Spacer(Modifier.height(14.dp))
        Text("Finding your photos…", style = MonoLabel.copy(fontSize = 13.sp), color = c.muted)
    }
}

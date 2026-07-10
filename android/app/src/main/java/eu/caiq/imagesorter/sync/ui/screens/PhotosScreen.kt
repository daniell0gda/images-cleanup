package eu.caiq.imagesorter.sync.ui.screens

import android.util.Log
import androidx.annotation.OptIn as AndroidOptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.caiq.imagesorter.sync.SyncApp
import eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.media.MediaListItem
import eu.caiq.imagesorter.sync.data.media.MediaUrls
import eu.caiq.imagesorter.sync.data.media.bearerDataSourceFactory
import eu.caiq.imagesorter.sync.data.media.buildVideoMediaItem
import eu.caiq.imagesorter.sync.data.media.insertDayHeaders
import eu.caiq.imagesorter.sync.serverAddressToBaseUrl
import eu.caiq.imagesorter.sync.ui.components.MediaPreviewPager
import eu.caiq.imagesorter.sync.ui.components.MediaThumb
import eu.caiq.imagesorter.sync.ui.components.previewIndexAfterDelete
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Test tag for the Photos tab root. */
const val PHOTOS_TAG = "photosScreen"

/** Test tag on each media cell in the gallery grid. */
const val PHOTOS_CELL_TAG = "photosCell"

/** Content description on the video play badge overlay (one per video cell). */
const val PHOTOS_VIDEO_BADGE_DESC = "Video"

/** Content description on a selected cell's check overlay (one per selected cell). */
const val PHOTOS_SELECTED_DESC = "Selected"

/** Marks a [MediaEntity] as video (server `kind`). */
private const val KIND_VIDEO = "video"

/** The grid item key for a timeline row (same scheme PhotosGrid uses): `h:day` / `m:id`. */
private fun mediaListItemKey(item: MediaListItem): String = when (item) {
    is MediaListItem.Header -> "h:${item.day}"
    is MediaListItem.Media -> "m:${item.entity.id}"
}

/** Fixed column count for the gallery grid (square thumbnails). */
private const val GRID_COLUMNS = 3

private const val TAG = "GOTODATE"

/**
 * The server media gallery: a `LazyVerticalGrid` of square thumbnails with sticky
 * day headers; tapping a cell reports its index among media-only items (headers
 * excluded), the same domain the preview pager pages over. The [cell] slot draws one
 * square thumbnail for an entity (Coil in production); video cells get a play badge.
 *
 * Stateless and source-agnostic so a Compose test can drive it with a fixed list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosGrid(
    items: List<MediaListItem>,
    onOpen: (mediaIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    selectedIds: Set<Long> = emptySet(),
    inSelectionMode: Boolean = false,
    onToggle: (MediaEntity) -> Unit = {},
    onLongPress: (MediaEntity) -> Unit = {},
    cell: @Composable (MediaEntity, Modifier) -> Unit,
) {
    val c = VaultTheme.colors
    // The media-only index for each flat position, so a tap maps to the pager's domain.
    val mediaIndexAt = remember(items) {
        var count = 0
        IntArray(items.size) { i -> if (items[i] is MediaListItem.Media) count++ else -1 }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .testTag(PHOTOS_TAG),
    ) {
        items.forEachIndexed { i, item ->
            when (item) {
                is MediaListItem.Header -> {
                    // Full-span day header. (LazyVerticalGrid stickyHeader isn't in
                    // foundation 1.7.x; a full-span item keeps the section structure.)
                    // Stable key so the grid preserves scroll position when newer pages
                    // prepend above the viewport (e.g. after a date seek) instead of drifting.
                    item(key = "h:${item.day}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            item.day,
                            style = MaterialTheme.typography.titleSmall,
                            color = c.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.ground)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                is MediaListItem.Media -> {
                    val mediaIndex = mediaIndexAt[i]
                    val entity = item.entity
                    item(key = "m:${entity.id}") {
                        MediaCell(
                            entity = entity,
                            selected = entity.id in selectedIds,
                            onClick = { if (inSelectionMode) onToggle(entity) else onOpen(mediaIndex) },
                            onLongClick = { onLongPress(entity) },
                            cell = cell,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    entity: MediaEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cell: @Composable (MediaEntity, Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .testTag(PHOTOS_CELL_TAG)
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        cell(entity, Modifier.fillMaxSize())
        if (entity.kind == KIND_VIDEO) {
            VideoBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        if (selected) SelectedBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
    }
}

/** A filled accent disc with a check, marking a selected cell. */
@Composable
private fun SelectedBadge(modifier: Modifier = Modifier) {
    val c = VaultTheme.colors
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(c.accent)
            .semantics { contentDescription = PHOTOS_SELECTED_DESC },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
    }
}

/** A small translucent play disc marking a video cell. */
@Composable
private fun VideoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .semantics { contentDescription = PHOTOS_VIDEO_BADGE_DESC },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * The live Photos tab. Observes the server timeline ([insertDayHeaders] applied),
 * renders [PhotosGrid] with Coil-loaded thumbnails, and opens [MediaPreviewPager]
 * on tap — Coil previews for images, ExoPlayer (non-zoomable page) for videos.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    modifier: Modifier = Modifier,
    onGoToAlbum: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val locator = (context.applicationContext as SyncApp).serviceLocator
    // Prefs (Keystore-backed) can be unavailable in a bare test harness; degrade to
    // an empty, untoken'd gallery rather than crashing the tab.
    val baseUrl = remember(locator) {
        runCatching { serverAddressToBaseUrl(locator.securePrefs.getServerAddress()) }.getOrNull().orEmpty()
    }
    val urls = remember(baseUrl) { MediaUrls(baseUrl) }
    val token = remember(locator) { runCatching { locator.securePrefs.getToken() }.getOrNull() }

    // Segment mode: a confirmed Go-To-Date picks a year/month/day segment, which swaps the grid
    // onto the bounded, server-backed segmentTimeline. Null = the normal newest-first timeline.
    var segment by remember { mutableStateOf<DateSegment?>(null) }
    var availableDates by remember { mutableStateOf<MediaDatesDto>(emptyMap()) }
    val segmentActive = segment != null

    // Profile filter: null = the all-profiles default; a profile id scopes the timeline to
    // media placed by that sync profile. The choices come from GET /api/sync/profiles.
    var profileFilter by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf<List<eu.caiq.imagesorter.sync.data.api.dto.ProfileDto>>(emptyList()) }
    LaunchedEffect(locator) {
        profiles = runCatching { locator.api.profiles() }.getOrDefault(emptyList())
    }

    val flow = remember(locator, segment, profileFilter) {
        runCatching {
            val repo = locator.mediaRepository
            val active = segment
            if (active == null) {
                repo.timeline(profileFilter).map { it.insertDayHeaders() }
            } else {
                repo.segmentTimeline(active.date, segmentDatePrefix(active)).map { it.insertDayHeaders() }
            }
        }.getOrNull()
    }
    val lazyItems = flow?.collectAsLazyPagingItems()
    val items = lazyItems?.itemSnapshotList?.items ?: emptyList()
    val mediaItems = remember(items) {
        items.filterIsInstance<MediaListItem.Media>().map { it.entity }
    }

    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var nameDialogAction by remember { mutableStateOf<AlbumSelectionAction?>(null) }
    var addPickerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val albumRepo = remember(locator) { runCatching { locator.albumRepository }.getOrNull() }
    val deviceName = remember { android.os.Build.MODEL }
    val inSelectionMode = selectedIds.isNotEmpty()

    val repository = remember(locator) { runCatching { locator.mediaRepository }.getOrNull() }
    val syncEngine = remember(locator) { runCatching { locator.syncEngine }.getOrNull() }
    // Load the available-dates tree once so adjacent-segment navigation can resolve against it.
    LaunchedEffect(repository) {
        if (repository != null) availableDates = runCatching { repository.availableDates() }.getOrDefault(emptyMap())
    }
    // The single in-progress signal shared by the auto-refresh timer and the pull gesture,
    // derived from Paging load state — no separately hand-maintained refreshing boolean.
    val isRefreshing = lazyItems?.loadState?.mediator?.refresh is LoadState.Loading
    // Set only by a user pull; drives the pull spinner so auto-refreshes show no spinner.
    var manualRefreshing by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Track a manual pull's refresh from start to settle, then hide the pull spinner.
    LaunchedEffect(manualRefreshing, lazyItems) {
        if (!manualRefreshing) return@LaunchedEffect
        val li = lazyItems
        if (li == null) {
            manualRefreshing = false
            return@LaunchedEffect
        }
        snapshotFlow { li.loadState.mediator?.refresh is LoadState.Loading }.first { it }
        snapshotFlow { li.loadState.mediator?.refresh is LoadState.Loading }.first { !it }
        manualRefreshing = false
    }

    // Back exits multi-select instead of leaving the tab. (The fullscreen preview
    // handles its own back via MediaPreviewPager and takes precedence when open.)
    BackHandler(enabled = inSelectionMode) { selectedIds = emptySet() }

    // Diagnostic: log every change to the grid's scroll position so a post-seek
    // drift (something else moving the viewport after the reset-to-0) is visible.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                Log.d(TAG, "gridState changed: firstVisibleItemIndex=$index offset=$offset segmentActive=$segmentActive")
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Pull-to-refresh wraps only the scrollable grid so its indicator sits over the grid
        // while the overlays (LatestChip, FAB, selection bar, preview, snackbar) stay on top.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = manualRefreshing,
            onRefresh = {
                manualRefreshing = true
                val li = lazyItems
                // Manual pull bypasses the 30s floor; skip while a refresh is already in flight
                // (the spinner just tracks that one). A pull while a segment is active exits
                // segment mode and refreshes the newest-first timeline.
                if (repository != null && li != null && !isRefreshing) {
                    scope.launch {
                        if (segmentActive) segment = null
                        li.refresh()
                        repository.refreshThrottle.markRefreshed(System.currentTimeMillis())
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Log.d(TAG, "render: showing grid, items.size=${items.size} firstVisibleItemIndex=${gridState.firstVisibleItemIndex} segmentActive=$segmentActive")
            // While a segment is active a horizontal swipe steps to the adjacent segment at the
            // same granularity (a swipe toward an absent segment is a no-op). Off segment mode
            // the grid keeps its plain vertical-only paging behaviour.
            val gridModifier = if (segmentActive) {
                Modifier.pointerInput(segment, availableDates) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        val active = segment ?: return@detectHorizontalDragGestures
                        segmentForSwipe(availableDates, active, swipeRightward = dragAmount > 0)?.let {
                            segment = it
                        }
                    }
                }
            } else {
                Modifier
            }
            PhotosGrid(
                items = items,
                onOpen = { previewIndex = it },
                state = gridState,
                selectedIds = selectedIds,
                inSelectionMode = inSelectionMode,
                onToggle = { entity ->
                    selectedIds = if (entity.id in selectedIds) selectedIds - entity.id else selectedIds + entity.id
                },
                onLongPress = { entity -> selectedIds = selectedIds + entity.id },
                modifier = gridModifier,
            ) { entity, cellModifier ->
                MediaThumb(
                    model = authedRequest(context, urls.thumb(entity.id), token),
                    modifier = cellModifier,
                )
            }
        }

        // The profile filter sits at the top-end of the gallery. Hidden while selecting or
        // previewing so it does not fight the selection bar or show through the viewer. A pick
        // rebuilds the timeline flow scoped to that profile (or clears back to all-profiles).
        if (!inSelectionMode && previewIndex == null) {
            ProfileFilter(
                options = profileFilterOptions(profiles),
                selected = profileFilter,
                onSelect = { profileFilter = it },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }

        if (inSelectionMode) {
            SelectionActionsBar(
                count = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onAction = { action ->
                    when (action) {
                        AlbumSelectionAction.AddToAlbum -> addPickerOpen = true
                        else -> nameDialogAction = action
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        nameDialogAction?.let { action ->
            if (albumRepo != null) {
                AlbumNameDialog(
                    defaultName = defaultAlbumName(java.time.LocalDate.now()),
                    onDismiss = { nameDialogAction = null },
                    onConfirm = { name ->
                        val ids = selectedIds.toList()
                        nameDialogAction = null
                        selectedIds = emptySet()
                        scope.launch {
                            runCatching {
                                runAlbumNameAction(
                                    action = action,
                                    name = name,
                                    mediaIds = ids,
                                    createdBy = deviceName,
                                    repo = albumRepo,
                                    copyToClipboard = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                                    confirm = { scope.launch { snackbarHostState.showSnackbar("Link copied") } },
                                    onCreated = { album ->
                                        scope.launch { confirmAlbumCreated(snackbarHostState, album.id, onGoToAlbum) }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }

        if (addPickerOpen && albumRepo != null) {
            AddToAlbumPicker(
                loadAlbums = { albumRepo.albums() },
                onPick = { albumId ->
                    val ids = selectedIds.toList()
                    addPickerOpen = false
                    selectedIds = emptySet()
                    scope.launch { runCatching { albumRepo.addItems(albumId, ids) } }
                },
                onDismiss = { addPickerOpen = false },
            )
        }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        val idx = previewIndex
        if (idx != null && idx in mediaItems.indices) {
            PhotosPreview(
                items = mediaItems,
                startIndex = idx,
                onClose = { previewIndex = null },
                onDelete = { deletedIndex ->
                    // Delete the item server-side (original file + index) and drop its cached
                    // Room row; that invalidates the timeline paging source so the item leaves
                    // the grid and the pager slides to the next one. The index update closes
                    // the preview once the last remaining item is deleted.
                    val entity = mediaItems.getOrNull(deletedIndex)
                    if (entity != null && repository != null) {
                        scope.launch { runCatching { repository.delete(entity.id) } }
                    }
                    previewIndex = previewIndexAfterDelete(mediaItems.size - 1, deletedIndex)
                },
            ) { entity -> MediaPreviewContent(entity, urls, token) }
        }

        if (repository != null) {
            // A confirmed segment shows its photos starting at the top: switching [segment]
            // swaps [flow] onto segmentTimeline (a fresh pager, getRefreshKey=null), so the new
            // list is presented from index 0 with no carried-over mid-list scroll position.
            LaunchedEffect(segment) {
                if (segment != null) gridState.scrollToItem(0)
            }
            val li = lazyItems
            if (li != null) {
                // Gate re-reads live Compose state each tick (derivedStateOf tracks all inputs),
                // so the loop's captured lambda never sees a stale value. While a segment is active
                // the gate is closed so auto-refresh never yanks the segment view out from under the user.
                val autoRefreshGate = remember {
                    derivedStateOf {
                        shouldAutoRefresh(
                            gridState.firstVisibleItemIndex,
                            segmentActive,
                            previewIndex != null,
                            selectedIds.isNotEmpty(),
                        )
                    }
                }
                // Scoped to RESUMED: stops when backgrounded/on tab switch, re-runs on return.
                // The persistent MediaRepository throttle keeps rapid re-entry from re-refreshing.
                LaunchedEffect(lifecycleOwner, li, repository) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        autoRefreshLoop(
                            now = { System.currentTimeMillis() },
                            isRefreshing = { li.loadState.mediator?.refresh is LoadState.Loading },
                            gateOpen = { autoRefreshGate.value },
                            throttle = repository.refreshThrottle,
                            refresh = { li.refresh() },
                        )
                    }
                }
                // Opening the Photos tab (this composition re-enters on first arrival and on
                // return from another tab) refreshes immediately, bypassing the 30s floor but
                // skipping while a refresh is already in flight.
                LaunchedEffect(Unit) {
                    refreshNow(
                        now = { System.currentTimeMillis() },
                        isRefreshing = { li.loadState.mediator?.refresh is LoadState.Loading },
                        throttle = repository.refreshThrottle,
                        refresh = { li.refresh() },
                    )
                }
                // Refresh when a sync batch finishes server-side grouping (session completed +
                // outcomes reported) so the newly synced items appear without user interaction.
                if (syncEngine != null) {
                    LaunchedEffect(li, repository, syncEngine) {
                        syncCompletionRefreshLoop(
                            completions = syncEngine.syncCompletions,
                            now = { System.currentTimeMillis() },
                            isRefreshing = { li.loadState.mediator?.refresh is LoadState.Loading },
                            throttle = repository.refreshThrottle,
                            refresh = { li.refresh() },
                        )
                    }
                }
            }
            val showLatest by remember(segmentActive) {
                derivedStateOf {
                    shouldShowLatestChip(
                        gridState.firstVisibleItemIndex,
                        segmentActive,
                        previewOpen = previewIndex != null,
                    )
                }
            }
            LatestChip(
                visible = showLatest,
                onClick = {
                    scope.launch {
                        handleLatestTap(
                            // A segment is a non-timeline view: tapping Latest exits it back to the
                            // newest-first timeline (which resumes its normal prepend/append paging).
                            isSeekActive = segmentActive,
                            resetToLatest = { segment = null },
                            scrollToTop = { gridState.animateScrollToItem(0) },
                        )
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
            // The Go-To-Date FAB shares the bottom edge with the selection action bar;
            // hide it while selecting so it doesn't sit under (and fight) that bar. When the
            // album-created bar is up it lifts a little so the bar isn't hidden behind it.
            if (!inSelectionMode) {
                val fabPadding = fabBottomPadding(snackbarHostState.currentSnackbarData != null)
                DatePickerFab(
                    onClick = { datePickerOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = fabPadding),
                )
            }
            if (datePickerOpen) {
                DatePickerModal(
                    loadDates = { repository.availableDates() },
                    onConfirm = { confirmed ->
                        Log.d(TAG, "DatePickerModal.onConfirm: segment=$confirmed, entering segment mode")
                        datePickerOpen = false
                        segment = confirmed
                    },
                    onDismiss = { datePickerOpen = false },
                )
            }
            // Subtle edge indicators: an adjacent segment exists in that direction. Hidden when
            // there is nothing to step to (or when no segment is active).
            segment?.let { active ->
                if (hasAdjacentSegment(availableDates, active, SegmentSide.LEFT)) {
                    SegmentEdgeIndicator(Alignment.CenterStart, SegmentSide.LEFT)
                }
                if (hasAdjacentSegment(availableDates, active, SegmentSide.RIGHT)) {
                    SegmentEdgeIndicator(Alignment.CenterEnd, SegmentSide.RIGHT)
                }
            }
        }
    }
}

/** Test tag on a segment edge indicator; suffixed with the side (`left` / `right`). */
const val SEGMENT_EDGE_INDICATOR_TAG = "segmentEdgeIndicator"

/**
 * A subtle chevron hugging one screen edge, hinting that a swipe that way steps to an adjacent
 * segment. Shown only while a segment is active and an adjacent segment exists on [side].
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SegmentEdgeIndicator(
    alignment: Alignment,
    side: SegmentSide,
) {
    val icon = if (side == SegmentSide.LEFT) {
        Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    } else {
        Icons.AutoMirrored.Rounded.KeyboardArrowRight
    }
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(4.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .testTag("$SEGMENT_EDGE_INDICATOR_TAG:${side.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
    }
}

/**
 * The fullscreen gallery preview overlay. Wraps [MediaPreviewPager], supplying a
 * Delete action in the bottom slot that reports the current page index to [onDelete].
 * Stateless and source-agnostic (the [image] slot draws one entity), so a Compose
 * test can drive the delete callback with a fixed list.
 */
@Composable
fun PhotosPreview(
    items: List<MediaEntity>,
    startIndex: Int,
    onClose: () -> Unit,
    onDelete: (mediaIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    image: @Composable (MediaEntity) -> Unit,
) {
    MediaPreviewPager(
        items = items,
        startIndex = startIndex,
        onClose = onClose,
        modifier = modifier,
        zoomable = { it.kind != KIND_VIDEO },
        landscape = { it.kind != KIND_VIDEO && (it.width ?: 0) > (it.height ?: 0) },
        actions = { index ->
            IconButton(onClick = { onDelete(index) }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Color.White)
            }
        },
        image = image,
    )
}

/**
 * Confirms a just-created album with a snackbar whose action navigates to it.
 * Because it carries an action the snackbar would otherwise stay indefinitely, so it
 * is shown under a [timeoutMillis] (5s) cap: the "Go to album" tap invokes [onGoToAlbum]
 * with [albumId]; otherwise the bar dismisses itself once the timeout elapses. Split out
 * so the confirm→navigate wiring (and the auto-dismiss) is testable.
 */
suspend fun confirmAlbumCreated(
    host: androidx.compose.material3.SnackbarHostState,
    albumId: Long,
    onGoToAlbum: (Long) -> Unit,
    timeoutMillis: Long = 5_000L,
) {
    val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
        host.showSnackbar(
            message = "Album created",
            actionLabel = "Go to album",
            duration = androidx.compose.material3.SnackbarDuration.Indefinite,
        )
    }
    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onGoToAlbum(albumId)
}

/**
 * Bottom padding for the Go-To-Date FAB. Lifts it clear of the album-created snackbar
 * (which shares the bottom edge and is drawn beneath the FAB) while a bar is visible, so
 * the bar — including its "Go to album" action — is not partially hidden behind the FAB.
 */
fun fabBottomPadding(snackbarVisible: Boolean): androidx.compose.ui.unit.Dp =
    if (snackbarVisible) 84.dp else 16.dp

/** The three album actions offered over a Photos-grid selection (§7.1). */
enum class AlbumSelectionAction { CreateAlbum, AddToAlbum, CreateLink }

/** Test tag on the Photos selection-mode action bar. */
const val ALBUM_SELECTION_BAR_TAG = "albumSelectionBar"

/** The default name both create actions prefill: `Album <ISO date>` (§3.6). */
fun defaultAlbumName(today: java.time.LocalDate): String = "Album $today"

/**
 * Runs a name-dialog confirm for the two create actions (§7.1). Create album just
 * creates; Create link creates, then shares, then copies the server-built
 * [eu.caiq.imagesorter.sync.data.api.dto.ShareDto.shareUrl] VERBATIM (§3.11) and
 * confirms it. [AlbumSelectionAction.AddToAlbum] does not flow through here.
 */
suspend fun runAlbumNameAction(
    action: AlbumSelectionAction,
    name: String,
    mediaIds: List<Long>,
    createdBy: String?,
    repo: eu.caiq.imagesorter.sync.data.media.AlbumRepository,
    copyToClipboard: (String) -> Unit,
    confirm: (String) -> Unit,
    onCreated: (eu.caiq.imagesorter.sync.data.api.dto.AlbumDto) -> Unit = {},
) {
    val album = repo.create(name, mediaIds, createdBy)
    if (action == AlbumSelectionAction.CreateLink) {
        val share = repo.share(album.id)
        copyToClipboard(share.shareUrl)
        confirm(share.shareUrl)
    } else {
        onCreated(album)
    }
}

/**
 * The Photos selection-mode action bar: selected count, a close control, and the
 * three album actions (§7.1). Stateless — actions are reported to [onAction].
 */
@Composable
fun SelectionActionsBar(
    count: Int,
    onAction: (AlbumSelectionAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ALBUM_SELECTION_BAR_TAG)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(c.surfaceHigh)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel selection", tint = c.text)
            }
            Text("$count selected", color = c.text)
        }
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.TextButton(onClick = { onAction(AlbumSelectionAction.CreateAlbum) }) { Text("Create album") }
            androidx.compose.material3.TextButton(onClick = { onAction(AlbumSelectionAction.AddToAlbum) }) { Text("Add to album") }
            androidx.compose.material3.TextButton(onClick = { onAction(AlbumSelectionAction.CreateLink) }) { Text("Create link") }
        }
    }
}

/**
 * The shared name dialog for Create album / Create link (§3.6). Prefilled with
 * [defaultName]; confirm reports the trimmed name (Create disabled when blank).
 */
@Composable
fun AlbumNameDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Name album",
    confirmLabel: String = "Create",
) {
    var name by remember { mutableStateOf(defaultName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Picks an existing album to add the current selection to (§7.1). Loads the list
 * lazily via [loadAlbums]; tapping an entry reports its id to [onPick].
 */
@Composable
fun AddToAlbumPicker(
    loadAlbums: suspend () -> List<eu.caiq.imagesorter.sync.data.api.dto.AlbumDto>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var albums by remember { mutableStateOf<List<eu.caiq.imagesorter.sync.data.api.dto.AlbumDto>>(emptyList()) }
    LaunchedEffect(Unit) { albums = runCatching { loadAlbums() }.getOrDefault(emptyList()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            androidx.compose.foundation.layout.Column {
                if (albums.isEmpty()) {
                    Text("No albums yet")
                } else {
                    albums.forEach { album ->
                        androidx.compose.material3.TextButton(onClick = { onPick(album.id) }) { Text(album.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Renders one media entity full-screen for the preview pager: an autoplaying
 * ExoPlayer page for video, otherwise a Coil preview image. Shared by the Photos
 * tab and album detail so both get identical (autoplaying) video playback.
 */
@Composable
fun MediaPreviewContent(entity: MediaEntity, urls: MediaUrls, token: String?) {
    if (entity.kind == KIND_VIDEO) {
        VideoPlayerPage(urls = urls, token = token, id = entity.id)
    } else {
        val context = LocalContext.current
        AsyncImage(
            model = authedRequest(
                context, urls.preview(entity.id), token,
                placeholderUrl = urls.thumb(entity.id),
            ),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A Coil [ImageRequest] for [url] carrying the bearer [token] as an HTTP header.
 * [crossfade] fades the decoded image in over its skeleton; the stable [memoryCacheKey]
 * lets a later request reuse this bitmap as a placeholder. When [placeholderUrl] is set
 * (the matching thumb of a full preview), its already-cached bitmap shows instantly while
 * the larger image loads.
 */
private fun authedRequest(
    context: android.content.Context,
    url: String,
    token: String?,
    placeholderUrl: String? = null,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .memoryCacheKey(url)
    if (placeholderUrl != null) builder.placeholderMemoryCacheKey(placeholderUrl)
    val headers = MediaUrls.authHeaders(token)
    if (headers.isNotEmpty()) {
        var net = NetworkHeaders.Builder()
        headers.forEach { (k, v) -> net = net.set(k, v) }
        builder.httpHeaders(net.build())
    }
    return builder.build()
}

/**
 * A single video page in the preview pager. Builds an [ExoPlayer] streaming
 * `/api/media/{id}/stream` (bearer token via [bearerDataSourceFactory]) and
 * **releases it on dispose** so leaving the page leaks no player.
 */
@AndroidOptIn(UnstableApi::class)
@Composable
private fun VideoPlayerPage(urls: MediaUrls, token: String?, id: Long) {
    val context = LocalContext.current
    val exoPlayer = remember(id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(bearerDataSourceFactory(token)))
            // Start playback after buffering ~0.5s instead of the 2.5s default, so
            // the first frame appears far sooner on a fast local network.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        /* bufferForPlaybackMs = */ 500,
                        /* bufferForPlaybackAfterRebufferMs = */ 1000,
                    )
                    .build()
            )
            .build()
            .apply {
                setMediaItem(buildVideoMediaItem(urls, id))
                playWhenReady = true
                prepare()
            }
    }
    // Show the thumb poster + spinner until the first frame is ready. The first play of a
    // non-web-safe video waits on a server-side transcode, so this can take a few seconds.
    var ready by remember(id) { mutableStateOf(exoPlayer.playbackState == Player.STATE_READY) }
    DisposableEffect(id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ready = state == Player.STATE_READY
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
            modifier = Modifier.fillMaxSize(),
        )
        if (!ready) {
            MediaThumb(
                model = authedRequest(context, urls.thumb(id), token),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            CircularProgressIndicator(color = VaultTheme.colors.accent)
        }
    }
}

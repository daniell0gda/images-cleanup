package imagesorter.sync.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imagesorter.sync.ui.theme.MonoLabel
import imagesorter.sync.ui.theme.VaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tag on the fullscreen preview overlay, so its presence is assertable. */
const val PREVIEW_TAG = "mediaPreview"

/**
 * The preview pager may page horizontally only when the current page is at rest (1:1).
 * While zoomed the swipe pans the zoomed page instead, so paging is disabled.
 */
fun previewPagingEnabled(scale: Float): Boolean = scale == 1f

/**
 * Whether a single-finger drag should pan the (zoomed) page. At 1:1 this is false so
 * the drag falls through to the [HorizontalPager] and the swipe advances the page;
 * the two-finger pinch that initiates a zoom is unaffected.
 */
fun previewCanPan(scale: Float): Boolean = scale > 1f

/**
 * The preview index to show after deleting the item that was at [deletedIndex], given
 * that [remainingCount] items remain. Returns null when nothing is left (the caller
 * closes the preview). Otherwise the index is clamped into the remaining bounds so a
 * last-item or stale out-of-range [deletedIndex] never produces an out-of-range page.
 */
fun previewIndexAfterDelete(remainingCount: Int, deletedIndex: Int): Int? =
    if (remainingCount <= 0) null else deletedIndex.coerceIn(0, remainingCount - 1)

/** Test tag on the selection-mode action/top bar. */
const val SELECTION_BAR_TAG = "selectionBar"

/** Content description on a tile's selected-check overlay (one per selected tile). */
const val SELECTION_CHECK_DESC = "Selected"

/**
 * A reusable photo-tile grid with Google-Photos-style multi-select. Generic over the
 * item type [T]; the caller supplies the [tile] visual, a stable [keyOf], and the
 * action set ([onTap]/[onToggle]/[onLongPress]). When [inSelectionMode] is true a
 * single tap toggles selection (and a check overlay marks selected tiles); otherwise
 * a tap opens the item and a long-press starts selection.
 *
 * Selection state is hoisted — this composable is a pure projection of [selectedKeys]
 * + [inSelectionMode] so it can be driven (and reused) by any screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> SelectableMediaGrid(
    items: List<T>,
    keyOf: (T) -> Any,
    selectedKeys: Set<Any>,
    inSelectionMode: Boolean,
    onTap: (Int) -> Unit,
    onToggle: (T) -> Unit,
    onLongPress: (T) -> Unit,
    tileTag: String,
    modifier: Modifier = Modifier,
    tile: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        itemsIndexed(items, key = { _, item -> keyOf(item) }) { index, item ->
            val selected = keyOf(item) in selectedKeys
            Box(
                modifier = Modifier
                    .testTag(tileTag)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .combinedClickable(
                        onClick = { if (inSelectionMode) onToggle(item) else onTap(index) },
                        onLongClick = { onLongPress(item) },
                    ),
            ) {
                tile(item)
                if (selected) SelectedOverlay(Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }
    }
}

/** A filled accent disc with a check glyph, drawn over a selected tile. */
@Composable
private fun SelectedOverlay(modifier: Modifier = Modifier) {
    val c = VaultTheme.colors
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(c.accent)
            .semantics { contentDescription = SELECTION_CHECK_DESC },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
    }
}

/**
 * The selection-mode bar: the selected count, a select-all control, and the bulk
 * action set ([onOverride] = "Sync anyway", [onDelete]). Reusable — the labels are
 * fixed for the not-people case but the actions are injected.
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onSelectAll: () -> Unit,
    onOverride: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .testTag(SELECTION_BAR_TAG)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "$count selected",
            style = MonoLabel.copy(fontSize = 13.sp, fontWeight = FontWeight.W600),
            color = c.text,
        )
        TextButton(onClick = onSelectAll) { Text("Select all") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOverride) { Text("Sync anyway") }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = c.coral)
        }
    }
}

/**
 * True while the [MediaPreviewPager] image slot is drawing the page the pager has
 * settled on — the item the user is actually looking at — and false for a neighbour
 * the pager pre-composes as a swipe brings it into view. A video page reads this to
 * autoplay only once it is the visible page (it still preloads while off-screen);
 * other content ignores it. Defaults to true so content shown outside a pager plays
 * as before.
 */
val LocalMediaPreviewPageActive = compositionLocalOf { true }

/**
 * Fullscreen, swipeable preview. Pages over [items] with a [HorizontalPager]. A page
 * is pinch-zoom/pan-able when [zoomable] returns true for its item (the default);
 * while zoomed in (scale != 1) paging is disabled so the swipe gesture doesn't fight
 * the pan. Non-zoomable pages (e.g. video) skip the transform and keep paging on.
 *
 * The bottom [actions] slot is empty by default (read-only callers show no buttons);
 * a caller (e.g. the not-people review) supplies its own action row, receiving the
 * current page index. The [image] slot renders one item full-bleed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> MediaPreviewPager(
    items: List<T>,
    startIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    zoomable: (T) -> Boolean = { true },
    actions: @Composable (index: Int) -> Unit = {},
    image: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    // The preview is a fullscreen overlay, not a nav destination, so the system
    // back gesture/button would otherwise fall through to the screen behind it
    // (or exit). Intercept it to close the preview instead.
    BackHandler { onClose() }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.size - 1),
    ) { items.size }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset the zoom whenever the visible page changes, so each photo opens at 1:1
    // and the pager regains its swipe.
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(PREVIEW_TAG)
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            // Only page when at rest (1:1); while zoomed the gesture pans instead.
            userScrollEnabled = previewPagingEnabled(scale),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageZoomable = zoomable(items[page])
            val transformed = pageZoomable && page == pagerState.currentPage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // canPan only at scale > 1f so a 1:1 single-finger drag falls
                    // through to the pager (otherwise transformable eats the swipe).
                    .then(
                        if (transformed) {
                            Modifier.transformable(transformState, canPan = { previewCanPan(scale) })
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (transformed) {
                            Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // A video page autoplays only while it is the settled (visible) page;
                // a neighbour pre-composed mid-swipe preloads but stays paused.
                CompositionLocalProvider(
                    LocalMediaPreviewPageActive provides (page == pagerState.settledPage),
                ) {
                    image(items[page])
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            actions(pagerState.currentPage)
        }
    }
}

/** Square edge (px) requested for the fullscreen preview image. */
private const val FULL_PX = 1600

private fun previewContentUri(mediaStoreId: Long, mimeType: String?): android.net.Uri {
    val collection = if (mimeType?.startsWith("video/") == true) {
        android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
    } else {
        android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
    }
    return android.content.ContentUris.withAppendedId(collection, mediaStoreId)
}

/**
 * Full-bleed local image for the preview pager. Loads via [android.content.ContentResolver.loadThumbnail]
 * at a large size (good enough for on-device review without a full decode), falling
 * back to the [fallbackSeed] gradient while loading or if the file is gone.
 */
@Composable
fun MediaFullImage(
    mediaStoreId: Long?,
    mimeType: String?,
    fallbackSeed: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(mediaStoreId, mimeType) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    if (mediaStoreId != null) {
        LaunchedEffect(mediaStoreId, mimeType) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver
                        .loadThumbnail(previewContentUri(mediaStoreId, mimeType), android.util.Size(FULL_PX, FULL_PX), null)
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(modifier.photoTile(fallbackSeed))
    }
}

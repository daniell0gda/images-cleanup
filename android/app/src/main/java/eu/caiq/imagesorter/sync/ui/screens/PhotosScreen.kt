package eu.caiq.imagesorter.sync.ui.screens

import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.caiq.imagesorter.sync.SyncApp
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.media.MediaListItem
import eu.caiq.imagesorter.sync.data.media.MediaUrls
import eu.caiq.imagesorter.sync.data.media.bearerDataSourceFactory
import eu.caiq.imagesorter.sync.data.media.buildVideoMediaItem
import eu.caiq.imagesorter.sync.data.media.insertDayHeaders
import eu.caiq.imagesorter.sync.serverAddressToBaseUrl
import eu.caiq.imagesorter.sync.ui.components.MediaPreviewPager
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme
import kotlinx.coroutines.flow.map

/** Test tag for the Photos tab root. */
const val PHOTOS_TAG = "photosScreen"

/** Test tag on each media cell in the gallery grid. */
const val PHOTOS_CELL_TAG = "photosCell"

/** Content description on the video play badge overlay (one per video cell). */
const val PHOTOS_VIDEO_BADGE_DESC = "Video"

/** Marks a [MediaEntity] as video (server `kind`). */
private const val KIND_VIDEO = "video"

/** Fixed column count for the gallery grid (square thumbnails). */
private const val GRID_COLUMNS = 3

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
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                    item {
                        MediaCell(item.entity, onClick = { onOpen(mediaIndex) }, cell = cell)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCell(
    entity: MediaEntity,
    onClick: () -> Unit,
    cell: @Composable (MediaEntity, Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .testTag(PHOTOS_CELL_TAG)
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        cell(entity, Modifier.fillMaxSize())
        if (entity.kind == KIND_VIDEO) {
            VideoBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
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
@Composable
fun PhotosScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locator = (context.applicationContext as SyncApp).serviceLocator
    // Prefs (Keystore-backed) can be unavailable in a bare test harness; degrade to
    // an empty, untoken'd gallery rather than crashing the tab.
    val baseUrl = remember(locator) {
        runCatching { serverAddressToBaseUrl(locator.securePrefs.getServerAddress()) }.getOrNull().orEmpty()
    }
    val urls = remember(baseUrl) { MediaUrls(baseUrl) }
    val token = remember(locator) { runCatching { locator.securePrefs.getToken() }.getOrNull() }

    val flow = remember(locator) {
        runCatching { locator.mediaRepository.timeline().map { it.insertDayHeaders() } }.getOrNull()
    }
    val items = if (flow != null) {
        flow.collectAsLazyPagingItems().itemSnapshotList.items
    } else {
        emptyList()
    }
    val mediaItems = remember(items) {
        items.filterIsInstance<MediaListItem.Media>().map { it.entity }
    }

    var previewIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        PhotosGrid(items = items, onOpen = { previewIndex = it }) { entity, cellModifier ->
            AsyncImage(
                model = authedRequest(context, urls.thumb(entity.id), token),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = cellModifier,
            )
        }

        val idx = previewIndex
        if (idx != null && idx in mediaItems.indices) {
            MediaPreviewPager(
                items = mediaItems,
                startIndex = idx,
                onClose = { previewIndex = null },
                zoomable = { it.kind != KIND_VIDEO },
            ) { entity ->
                if (entity.kind == KIND_VIDEO) {
                    VideoPlayerPage(urls = urls, token = token, id = entity.id)
                } else {
                    AsyncImage(
                        model = authedRequest(context, urls.preview(entity.id), token),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** A Coil [ImageRequest] for [url] carrying the bearer [token] as an HTTP header. */
private fun authedRequest(context: android.content.Context, url: String, token: String?): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url)
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
            .build()
            .apply {
                setMediaItem(buildVideoMediaItem(urls, id))
                prepare()
            }
    }
    DisposableEffect(id) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
        modifier = Modifier.fillMaxSize(),
    )
}

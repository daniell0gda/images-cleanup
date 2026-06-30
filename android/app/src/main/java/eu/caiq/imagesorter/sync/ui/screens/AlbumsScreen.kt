package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.caiq.imagesorter.sync.SyncApp
import eu.caiq.imagesorter.sync.data.api.dto.AlbumDto
import eu.caiq.imagesorter.sync.data.api.dto.MediaItemDto
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.media.AlbumRepository
import eu.caiq.imagesorter.sync.data.media.MediaListItem
import eu.caiq.imagesorter.sync.data.media.MediaUrls
import eu.caiq.imagesorter.sync.data.media.insertDayHeaders
import eu.caiq.imagesorter.sync.serverAddressToBaseUrl
import eu.caiq.imagesorter.sync.ui.components.MediaThumb
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Test tag for the Albums tab list root. */
const val ALBUMS_TAG = "albumsScreen"

/** Test tag for the album-detail root (shown when an album is opened). */
const val ALBUM_DETAIL_TAG = "albumDetail"

/**
 * Maps an album's timeline-style items (already newest-first from the server) to
 * [MediaEntity] so the detail screen can reuse the gallery cell/preview
 * components. [orderKey] mirrors the list position so identity/order is preserved.
 */
fun albumItemsToEntities(items: List<MediaItemDto>): List<MediaEntity> =
    items.mapIndexed { index, dto ->
        MediaEntity(
            id = dto.id,
            kind = dto.kind,
            dateTaken = dto.dateTaken,
            width = dto.width,
            height = dto.height,
            orderKey = index.toLong(),
        )
    }

/**
 * Removes the given media from the album (membership only, §3.8/§8). Calls the
 * membership DELETE endpoint via [AlbumRepository.removeItems]; it MUST NOT invoke
 * the system delete dialog ([onSystemDelete] is never called here) because
 * removing from an album is non-destructive to the underlying files.
 */
suspend fun removeFromAlbum(
    albumId: Long,
    mediaIds: List<Long>,
    repo: AlbumRepository,
    onSystemDelete: (List<Long>) -> Unit = {},
): AlbumDto = repo.removeItems(albumId, mediaIds)

/**
 * Shares an album (§7.2): mints a fresh token and copies the server-built share URL
 * VERBATIM (§3.11) — the app never constructs share URLs. Returns the URL so the
 * caller can reflect the now-shared state. [confirm] surfaces a "copied" message.
 */
suspend fun shareAlbumLink(
    albumId: Long,
    repo: AlbumRepository,
    copyToClipboard: (String) -> Unit,
    confirm: (String) -> Unit,
): String {
    val share = repo.share(albumId)
    copyToClipboard(share.shareUrl)
    confirm(share.shareUrl)
    return share.shareUrl
}

/**
 * One album tile: cover thumbnail (square), name, item count, and `created_by`
 * (§7.2). Stateless — [coverModel] is the Coil request (or null for a placeholder)
 * and tapping reports via [onOpen].
 */
@Composable
fun AlbumTile(
    album: AlbumDto,
    coverModel: Any?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (coverModel != null) {
                MediaThumb(
                    model = coverModel,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = c.muted)
            }
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${album.itemCount} items",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        album.createdBy?.let { by ->
            Text(by, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
        }
    }
}

/**
 * The live Albums tab. Lists album tiles; opening one swaps to a detail screen via
 * LOCAL state ([selectedAlbumId]) with its own back affordance (§3.7) — NOT a new
 * global AppScreen.
 */
@Composable
fun AlbumsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locator = (context.applicationContext as SyncApp).serviceLocator
    val baseUrl = remember(locator) {
        runCatching { serverAddressToBaseUrl(locator.securePrefs.getServerAddress()) }.getOrNull().orEmpty()
    }
    val urls = remember(baseUrl) { MediaUrls(baseUrl) }
    val token = remember(locator) { runCatching { locator.securePrefs.getToken() }.getOrNull() }
    val repo = remember(locator) { runCatching { locator.albumRepository }.getOrNull() }

    var albums by remember { mutableStateOf<List<AlbumDto>>(emptyList()) }
    var selectedAlbumId by remember { mutableStateOf<Long?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(repo, reloadKey) {
        if (repo != null) albums = runCatching { repo.albums() }.getOrDefault(emptyList())
    }

    val openAlbum = selectedAlbumId?.let { id -> albums.find { it.id == id } }
    if (openAlbum != null && repo != null) {
        AlbumDetail(
            album = openAlbum,
            repo = repo,
            urls = urls,
            token = token,
            onBack = { selectedAlbumId = null; reloadKey++ },
        )
    } else {
        AlbumsList(
            albums = albums,
            cover = { album -> coverRequest(context, urls, token, album) },
            onOpen = { selectedAlbumId = it },
            modifier = modifier,
        )
    }
}

/** Stateless album grid; [cover] builds a Coil request for an album's cover thumbnail. */
@Composable
private fun AlbumsList(
    albums: List<AlbumDto>,
    cover: (AlbumDto) -> Any?,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .testTag(ALBUMS_TAG)
            .padding(8.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumTile(album = album, coverModel = cover(album), onOpen = { onOpen(album.id) })
        }
    }
}

/**
 * Album-detail screen (local state, own back). Shows the album's items newest-first
 * (server order), reusing [PhotosGrid]/[PhotosPreview]. Add photos adds membership
 * (§7.2); long-press → Remove from album removes membership only and never triggers
 * the system delete dialog (§3.8/§8).
 */
@Composable
private fun AlbumDetail(
    album: AlbumDto,
    repo: AlbumRepository,
    urls: MediaUrls,
    token: String?,
    onBack: () -> Unit,
) {
    val albumId = album.id
    val context = LocalContext.current
    val c = VaultTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // Share state is reflected locally so the menu updates immediately after a
    // share/revoke, without re-fetching the whole album list.
    var shareState by remember(albumId) { mutableStateOf(album) }
    var shareMenuOpen by remember(albumId) { mutableStateOf(false) }
    var renameOpen by remember(albumId) { mutableStateOf(false) }
    var entities by remember(albumId) { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var reloadKey by remember(albumId) { mutableStateOf(0) }
    var selectedIds by remember(albumId) { mutableStateOf<Set<Long>>(emptySet()) }
    var addPickerOpen by remember(albumId) { mutableStateOf(false) }
    var previewIndex by remember(albumId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(albumId, reloadKey) {
        entities = runCatching { albumItemsToEntities(repo.items(albumId)) }.getOrDefault(emptyList())
    }

    val listItems = remember(entities) { entities.map { MediaListItem.Media(it) } }
    val inSelectionMode = selectedIds.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize().testTag(ALBUM_DETAIL_TAG)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(c.ground).padding(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = c.text)
                }
                Text(
                    shareState.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { renameOpen = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Rename album", tint = c.text)
                }
                ShareMenu(
                    shared = shareState.shared,
                    expanded = shareMenuOpen,
                    onExpandedChange = { shareMenuOpen = it },
                    onCopyLink = {
                        shareMenuOpen = false
                        shareState.shareUrl?.let { url ->
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                            scope.launch { snackbarHostState.showSnackbar("Link copied") }
                        }
                    },
                    onCreateLink = {
                        shareMenuOpen = false
                        scope.launch {
                            runCatching {
                                shareAlbumLink(
                                    albumId = albumId,
                                    repo = repo,
                                    copyToClipboard = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                                    confirm = {},
                                )
                            }.onSuccess { url ->
                                shareState = shareState.copy(shared = true, shareUrl = url)
                                snackbarHostState.showSnackbar("Link created & copied")
                            }
                        }
                    },
                    onRevoke = {
                        shareMenuOpen = false
                        scope.launch {
                            runCatching { repo.revoke(albumId) }.onSuccess {
                                shareState = shareState.copy(shared = false, shareUrl = null)
                                snackbarHostState.showSnackbar("Link revoked")
                            }
                        }
                    },
                )
                IconButton(onClick = { addPickerOpen = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add photos", tint = c.text)
                }
            }
            PhotosGrid(
                items = listItems,
                onOpen = { previewIndex = it },
                selectedIds = selectedIds,
                inSelectionMode = inSelectionMode,
                onToggle = { entity ->
                    selectedIds = if (entity.id in selectedIds) selectedIds - entity.id else selectedIds + entity.id
                },
                onLongPress = { entity -> selectedIds = selectedIds + entity.id },
                modifier = Modifier.weight(1f),
            ) { entity, cellModifier ->
                MediaThumb(
                    model = albumRequest(context, urls.thumb(entity.id), token),
                    modifier = cellModifier,
                )
            }
        }

        if (inSelectionMode) {
            RemoveFromAlbumBar(
                count = selectedIds.size,
                onClose = { selectedIds = emptySet() },
                onRemove = {
                    val ids = selectedIds.toList()
                    selectedIds = emptySet()
                    scope.launch {
                        runCatching { removeFromAlbum(albumId, ids, repo) }
                        reloadKey++
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        if (addPickerOpen) {
            AddPhotosPicker(
                urls = urls,
                token = token,
                onConfirm = { ids ->
                    addPickerOpen = false
                    scope.launch {
                        runCatching { repo.addItems(albumId, ids) }
                        reloadKey++
                    }
                },
                onDismiss = { addPickerOpen = false },
            )
        }

        if (renameOpen) {
            AlbumNameDialog(
                defaultName = shareState.name,
                title = "Rename album",
                confirmLabel = "Rename",
                onConfirm = { newName ->
                    renameOpen = false
                    scope.launch {
                        runCatching { repo.rename(albumId, newName) }
                            .onSuccess { shareState = it; snackbarHostState.showSnackbar("Renamed") }
                    }
                },
                onDismiss = { renameOpen = false },
            )
        }

        val idx = previewIndex
        if (idx != null && idx in entities.indices) {
            PhotosPreview(
                items = entities,
                startIndex = idx,
                onClose = { previewIndex = null },
                onDelete = { previewIndex = null },
            ) { entity -> MediaPreviewContent(entity, urls, token) }
        }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Test tag on the album-detail share/link control. */
const val ALBUM_SHARE_MENU_TAG = "albumShareMenu"

/**
 * The album-detail share control (§7.2): a link icon (accent-tinted when the album
 * is already shared) that opens a menu. When [shared] the menu offers **Copy link**
 * and **Stop sharing**; otherwise a single **Create link**. Stateless — the actions
 * are reported to the caller, which performs the share/revoke and reflects the state.
 */
@Composable
private fun ShareMenu(
    shared: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCopyLink: () -> Unit,
    onCreateLink: () -> Unit,
    onRevoke: () -> Unit,
) {
    val c = VaultTheme.colors
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag(ALBUM_SHARE_MENU_TAG),
        ) {
            Icon(
                Icons.Rounded.Link,
                contentDescription = "Share link",
                tint = if (shared) c.accent else c.text,
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            if (shared) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Copy link") },
                    onClick = onCopyLink,
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Stop sharing") },
                    onClick = onRevoke,
                )
            } else {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Create link") },
                    onClick = onCreateLink,
                )
            }
        }
    }
}

/** Test tag on the album-detail Remove-from-album bar. */
const val ALBUM_REMOVE_BAR_TAG = "albumRemoveBar"

/** Test tag on the Add-photos timeline picker root. */
const val ALBUM_ADD_PICKER_TAG = "albumAddPicker"

/**
 * Album-detail selection bar offering only Remove from album (membership delete).
 * Deliberately exposes no file-delete action so removal can never be destructive.
 */
@Composable
private fun RemoveFromAlbumBar(
    count: Int,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ALBUM_REMOVE_BAR_TAG)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceHigh)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection", tint = c.text)
        }
        Text("$count selected", color = c.text, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove from album") }
    }
}

/**
 * Add photos (§7.2): a full-screen timeline picker reusing [PhotosGrid] in
 * selection mode. Confirm reports the selected media ids to [onConfirm], which adds
 * them as membership (`POST /api/albums/{id}/items`). Reads the live server
 * timeline so the picker mirrors the Photos tab.
 */
@Composable
private fun AddPhotosPicker(
    urls: MediaUrls,
    token: String?,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val c = VaultTheme.colors
    val locator = (context.applicationContext as SyncApp).serviceLocator
    val flow = remember(locator) {
        runCatching { locator.mediaRepository.timeline().map { it.insertDayHeaders() } }.getOrNull()
    }
    val lazyItems = flow?.collectAsLazyPagingItems()
    val items = lazyItems?.itemSnapshotList?.items ?: emptyList()
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    Column(modifier = Modifier.fillMaxSize().background(c.ground).testTag(ALBUM_ADD_PICKER_TAG)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel add", tint = c.text)
            }
            Text("${selectedIds.size} selected", color = c.text, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(selectedIds.toList()) },
                enabled = selectedIds.isNotEmpty(),
            ) { Text("Add") }
        }
        PhotosGrid(
            items = items,
            onOpen = {},
            selectedIds = selectedIds,
            inSelectionMode = true,
            onToggle = { entity ->
                selectedIds = if (entity.id in selectedIds) selectedIds - entity.id else selectedIds + entity.id
            },
            onLongPress = { entity -> selectedIds = selectedIds + entity.id },
            modifier = Modifier.weight(1f),
        ) { entity, cellModifier ->
            MediaThumb(
                model = albumRequest(context, urls.thumb(entity.id), token),
                modifier = cellModifier,
            )
        }
    }
}

private fun coverRequest(context: android.content.Context, urls: MediaUrls, token: String?, album: AlbumDto): Any? {
    val coverId = album.coverMediaId ?: return null
    return albumRequest(context, urls.thumb(coverId), token)
}

/**
 * A Coil [ImageRequest] for [url] carrying the bearer [token] as an HTTP header.
 * [crossfade] fades the thumbnail in over its skeleton; the stable [memoryCacheKey]
 * (the url) lets the fullscreen preview reuse this bitmap as its placeholder.
 */
private fun albumRequest(context: android.content.Context, url: String, token: String?): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .memoryCacheKey(url)
    val headers = MediaUrls.authHeaders(token)
    if (headers.isNotEmpty()) {
        var net = NetworkHeaders.Builder()
        headers.forEach { (k, v) -> net = net.set(k, v) }
        builder.httpHeaders(net.build())
    }
    return builder.build()
}

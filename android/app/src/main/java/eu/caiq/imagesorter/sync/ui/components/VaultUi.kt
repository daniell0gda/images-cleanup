package eu.caiq.imagesorter.sync.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.caiq.imagesorter.sync.domain.model.SyncStatus
import eu.caiq.imagesorter.sync.ui.theme.MonoLabel
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme
import kotlinx.coroutines.delay

/** Small mono uppercase kicker used above titles. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MonoLabel.copy(fontSize = 11.5.sp, letterSpacing = 2.6.sp, fontWeight = FontWeight.W600),
        color = VaultTheme.colors.accent,
        modifier = modifier,
    )
}

/** Clickable that springs down on press — the app's standard tactile feedback. */
fun Modifier.clickableScale(scale: Float = 0.97f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
}

/**
 * Fades + lifts its content into place once, after [delayMs]. Used to stagger
 * list/grid entrances. Re-keys via [key] so a fresh entrance can be replayed.
 */
@Composable
fun AppearOnEntry(
    delayMs: Int = 0,
    key: Any? = Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffectDelay(delayMs, key) { shown = true }
    val a by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 440, easing = EaseOutCubic),
        label = "appear",
    )
    Box(modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 22f }) { content() }
}

@Composable
private fun LaunchedEffectDelay(delayMs: Int, key: Any?, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) {
        delay(delayMs.toLong())
        block()
    }
}

/** Status → accent/amber/coral/muted mapping for badges and rings. */
@Composable
fun statusColor(status: SyncStatus): Color = when (status) {
    SyncStatus.SYNCED -> VaultTheme.colors.accent
    SyncStatus.IN_PROGRESS -> VaultTheme.colors.amber
    SyncStatus.FAILED -> VaultTheme.colors.coral
    SyncStatus.PENDING -> VaultTheme.colors.muted
}

private val TILE_PALETTE = listOf(
    Color(0xFF3A6EA5) to Color(0xFFC0D6DF),
    Color(0xFFE8A87C) to Color(0xFFC38D9E),
    Color(0xFF41B3A3) to Color(0xFF85CDCA),
    Color(0xFF2C3E50) to Color(0xFF4CA1AF),
    Color(0xFF5C7457) to Color(0xFFA8C686),
    Color(0xFF6A4C93) to Color(0xFFB8B8FF),
    Color(0xFF114357) to Color(0xFFF29492),
    Color(0xFF02AAB0) to Color(0xFF00CDAC),
)

/**
 * A deterministic gradient stand-in for a photo thumbnail, derived from [seed]
 * (the item name). Used as the fallback by [MediaThumbnail] while the real
 * thumbnail loads, or when no local MediaStore id is available (e.g. a synced item
 * already removed from the phone).
 */
fun Modifier.photoTile(seed: String): Modifier = composed {
    val pair = TILE_PALETTE[(seed.hashCode() and 0x7fffffff) % TILE_PALETTE.size]
    clip(androidx.compose.material3.MaterialTheme.shapes.small)
        .background(Brush.linearGradient(listOf(pair.first, pair.second)))
        .fillMaxSize()
}

/** Square edge (px) requested from MediaStore for grid thumbnails. */
private const val THUMB_PX = 256

private fun contentUriFor(mediaStoreId: Long, mimeType: String?): android.net.Uri {
    val collection = if (mimeType?.startsWith("video/") == true) {
        android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
    } else {
        android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
    }
    return android.content.ContentUris.withAppendedId(collection, mediaStoreId)
}

/**
 * Renders the real on-device thumbnail for a media item. Loads via
 * [android.content.ContentResolver.loadThumbnail] (API 29+, so always available at
 * this app's minSdk 33), which generates frames for videos too. While loading — or
 * if [mediaStoreId] is null / the file is gone — it shows the [fallbackSeed]
 * gradient instead of an empty box.
 */
@Composable
fun MediaThumbnail(
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
        androidx.compose.runtime.LaunchedEffect(mediaStoreId, mimeType) {
            bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver
                        .loadThumbnail(contentUriFor(mediaStoreId, mimeType), android.util.Size(THUMB_PX, THUMB_PX), null)
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        androidx.compose.foundation.Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
                .clip(androidx.compose.material3.MaterialTheme.shapes.small)
                .fillMaxSize(),
        )
    } else {
        Box(modifier.photoTile(fallbackSeed))
    }
}

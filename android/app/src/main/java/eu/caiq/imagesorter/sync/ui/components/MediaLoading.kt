package eu.caiq.imagesorter.sync.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/**
 * A slow horizontal light-sweep over the surface tones — the skeleton shown while a
 * thumbnail is still decoding. Subtle by design (the petrol palette is dark), it reads
 * as "loading" without flashing. The sweep is driven entirely in the draw phase, so it
 * animates without recomposing.
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val c = VaultTheme.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    return this.drawBehind {
        val width = size.width
        // Sweep a highlight band from off the left edge to off the right edge.
        val startX = -width + progress.value * (width * 2f)
        drawRect(
            Brush.linearGradient(
                colors = listOf(c.surface, c.surfaceHigh, c.surface),
                start = Offset(startX, 0f),
                end = Offset(startX + width, 0f),
            ),
        )
    }
}

/**
 * A network thumbnail with a built-in loading skeleton. Renders [model] (a Coil request)
 * over a [shimmer] that shows until the image succeeds; pair the request with
 * `crossfade(true)` so the decoded image fades in over the skeleton rather than popping.
 *
 * Use for grid tiles, album covers, and video posters — anywhere a square-ish remote
 * thumbnail loads. The fullscreen preview uses a thumb-as-placeholder request instead
 * (see the screens), so it shows the already-cached tile immediately.
 */
@Composable
fun MediaThumb(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val painter = rememberAsyncImagePainter(model = model, contentScale = contentScale)
    val state by painter.state.collectAsState()
    Box(modifier) {
        if (state !is AsyncImagePainter.State.Success) {
            Box(Modifier.matchParentSize().shimmer())
        }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
        )
    }
}

package imagesorter.sync.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import imagesorter.sync.data.media.MediaListItem

/** Test tag on the "↑ Latest" chip. */
const val LATEST_CHIP_TAG = "latestChip"

/**
 * Whether the "↑ Latest" chip should be shown. It appears once scrolled past the
 * first item, and also whenever a date seek is active — after a seek the user sits
 * at the anchor (index 0) and still needs a one-tap way back to the newest timeline.
 * While the fullscreen viewer is open ([previewOpen]) the chip is always hidden so it
 * does not show through the overlay; closing it restores the scroll/seek rules.
 */
fun shouldShowLatestChip(
    firstVisibleItemIndex: Int,
    isSeekActive: Boolean,
    previewOpen: Boolean = false,
): Boolean = !previewOpen && (isSeekActive || firstVisibleItemIndex > 0)

/**
 * Resolves a "↑ Latest" tap: when a date seek is active, clears it (which invalidates
 * the pager back to newest-first) before scrolling; otherwise just scrolls to the top.
 * Pure orchestration so the branch is unit-tested without Compose.
 */
suspend fun handleLatestTap(
    isSeekActive: Boolean,
    resetToLatest: suspend () -> Unit,
    scrollToTop: suspend () -> Unit,
) {
    if (isSeekActive) resetToLatest()
    scrollToTop()
}

/**
 * The flat grid index the viewport should anchor at after a Go-To-Date seek, computed
 * from the (settled) timeline snapshot.
 *
 * The seek REFRESH stores the on-or-before-date anchor at orderKey 0 and eager-loads the
 * newer photos above it at negative orderKeys; [insertDayHeaders] then interleaves
 * [MediaListItem.Header] rows (including one before the very first item). So the anchor's
 * flat position is NOT a simple count of newer items — it is the index of the first media
 * row whose `orderKey >= 0` (the on-or-before-date photo; newer photos are negative). We
 * back up onto that photo's day header when present so the date's section title sits at the
 * top. Anchoring here (rather than at index 0) keeps the viewport off the prepend edge so
 * Paging does not auto-cascade PREPEND back to latest. Returns 0 when no anchor is present.
 */
fun seekAnchorFlatIndex(items: List<MediaListItem>): Int {
    val mediaIndex = items.indexOfFirst { it is MediaListItem.Media && it.entity.orderKey >= 0L }
    if (mediaIndex < 0) return 0
    return if (mediaIndex > 0 && items[mediaIndex - 1] is MediaListItem.Header) mediaIndex - 1 else mediaIndex
}

/**
 * The "↑ Latest" chip that returns the gallery to the newest-first top. Fades in/out
 * via [AnimatedVisibility] as [visible] toggles; reports taps through [onClick].
 */
@Composable
fun LatestChip(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        AssistChip(
            onClick = onClick,
            label = { Text("Latest") },
            leadingIcon = {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.testTag(LATEST_CHIP_TAG),
        )
    }
}

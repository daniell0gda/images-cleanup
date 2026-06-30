package eu.caiq.imagesorter.sync.ui

import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.media.MediaListItem
import eu.caiq.imagesorter.sync.ui.screens.handleLatestTap
import eu.caiq.imagesorter.sync.ui.screens.seekAnchorFlatIndex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure, Compose-free decision logic that makes a Go-To-Date seek "stick": the flat grid
 * index the viewport should anchor at (accounting for [insertDayHeaders] rows), and the
 * Latest-chip reset ordering. The await-the-refresh-before-scrolling guarantee lives in a
 * Compose `LaunchedEffect` (snapshotFlow on loadState) and is verified on-device.
 */
class GoToDateSeekScrollTest {

    private fun media(orderKey: Long, day: String = "2024-01-01") =
        MediaListItem.Media(
            MediaEntity(
                id = orderKey + 1_000L,
                kind = "image",
                dateTaken = "${day}T00:00:00",
                width = 1,
                height = 1,
                orderKey = orderKey,
            ),
        )

    private fun header(day: String) = MediaListItem.Header(day)

    // --- Seek anchor flat index (header-aware) -----------------------------------

    @Test
    fun anchorIsFirstNonNegativeMediaBackedOntoItsDayHeader() {
        // Two newer photos (negative orderKeys) above the seek anchor (orderKey 0), each day
        // group prefixed by a header — exactly what insertDayHeaders produces. The anchor's
        // flat index is the header that precedes the first orderKey>=0 media.
        val items = listOf(
            header("2024-02-10"), media(-2, "2024-02-10"),
            header("2024-02-05"), media(-1, "2024-02-05"),
            header("2024-01-01"), media(0, "2024-01-01"), media(1, "2024-01-01"),
        )
        // header("2024-01-01") sits at flat index 4, the photo (orderKey 0) at 5 → anchor = 4.
        assertEquals(4, seekAnchorFlatIndex(items))
    }

    @Test
    fun anchorIsTheMediaItselfWhenNoPrecedingHeader() {
        // Defensive: if the anchor media is not immediately preceded by a header, anchor on it.
        val items = listOf(media(-1, "2024-02-05"), media(0, "2024-01-01"))
        assertEquals(1, seekAnchorFlatIndex(items))
    }

    @Test
    fun anchorIsZeroWhenSeekLandedOnLatestSoNoNewerExist() {
        // Seek hit the latest page: no negative orderKeys, first media is the top → index of
        // the leading header (0), i.e. the viewport stays at the top.
        val items = listOf(header("2024-01-01"), media(0), media(1))
        assertEquals(0, seekAnchorFlatIndex(items))
    }

    @Test
    fun anchorIsZeroWhenSnapshotHasNoMediaYet() {
        assertEquals(0, seekAnchorFlatIndex(emptyList()))
    }

    // --- Latest chip clears an active seek ---------------------------------------

    @Test
    fun latestTapWithActiveSeekResetsThenScrolls() = runTest {
        val calls = mutableListOf<String>()
        handleLatestTap(
            isSeekActive = true,
            resetToLatest = { calls.add("reset") },
            scrollToTop = { calls.add("scroll") },
        )
        assertEquals(listOf("reset", "scroll"), calls)
    }
}

package eu.caiq.imagesorter.sync.ui

import eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto
import eu.caiq.imagesorter.sync.ui.screens.DatePickerLevel
import eu.caiq.imagesorter.sync.ui.screens.DateSegment
import eu.caiq.imagesorter.sync.ui.screens.SegmentSide
import eu.caiq.imagesorter.sync.ui.screens.adjacentSegment
import eu.caiq.imagesorter.sync.ui.screens.hasAdjacentSegment
import eu.caiq.imagesorter.sync.ui.screens.segmentDatePrefix
import eu.caiq.imagesorter.sync.ui.screens.segmentForSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segment-navigation state logic (Compose-free): resolving the adjacent segment at the
 * same granularity against the server's available-dates tree, the edge-indicator flags,
 * the horizontal-swipe → segment mapping, and the (fromDate, datePrefix) mapping used to
 * drive [eu.caiq.imagesorter.sync.data.media.MediaRepository.segmentTimeline]. The actual
 * gesture and edge-indicator rendering are thin Compose wrappers over these decisions.
 */
class SegmentNavigationTest {

    // A three-year tree with gaps, so "nearest non-empty" resolution is exercised.
    private val dates: MediaDatesDto = mapOf(
        "2022" to mapOf("11" to listOf(4, 20)),
        "2024" to mapOf("01" to listOf(2), "03" to listOf(5, 12)),
        "2026" to mapOf("07" to listOf(8)),
    )

    // --- datePrefix / fromDate mapping -------------------------------------------

    @Test
    fun datePrefixIsTheSharedIsoPrefixPerGranularity() {
        assertEquals("2024-", segmentDatePrefix(DateSegment("2024-12-31", DatePickerLevel.YEAR)))
        assertEquals("2024-03-", segmentDatePrefix(DateSegment("2024-03-31", DatePickerLevel.MONTH)))
        assertEquals("2024-03-10", segmentDatePrefix(DateSegment("2024-03-10", DatePickerLevel.DAY)))
    }

    // --- adjacent year -----------------------------------------------------------

    @Test
    fun previousAndNextYearResolveToNearestNonEmptyYear() {
        val current = DateSegment("2024-12-31", DatePickerLevel.YEAR)
        // 2023 has no photos, so the nearest previous non-empty year is 2022.
        assertEquals(
            DateSegment("2022-12-31", DatePickerLevel.YEAR),
            adjacentSegment(dates, current, SegmentSide.LEFT),
        )
        // 2025 is empty, so the nearest next non-empty year is 2026.
        assertEquals(
            DateSegment("2026-12-31", DatePickerLevel.YEAR),
            adjacentSegment(dates, current, SegmentSide.RIGHT),
        )
    }

    // --- adjacent month ----------------------------------------------------------

    @Test
    fun previousMonthCrossesYearBoundaryToNearestNonEmptyMonth() {
        val current = DateSegment("2024-01-31", DatePickerLevel.MONTH)
        // 2024-01's previous non-empty month is 2022-11 (crossing the year gap).
        assertEquals(
            DateSegment("2022-11-30", DatePickerLevel.MONTH),
            adjacentSegment(dates, current, SegmentSide.LEFT),
        )
        // 2024-01's next non-empty month is 2024-03.
        assertEquals(
            DateSegment("2024-03-31", DatePickerLevel.MONTH),
            adjacentSegment(dates, current, SegmentSide.RIGHT),
        )
    }

    // --- adjacent day ------------------------------------------------------------

    @Test
    fun previousAndNextDayResolveWithinTheAvailableDays() {
        val current = DateSegment("2024-03-05", DatePickerLevel.DAY)
        assertEquals(
            DateSegment("2024-01-02", DatePickerLevel.DAY),
            adjacentSegment(dates, current, SegmentSide.LEFT),
        )
        assertEquals(
            DateSegment("2024-03-12", DatePickerLevel.DAY),
            adjacentSegment(dates, current, SegmentSide.RIGHT),
        )
    }

    // --- edge indicators / no-op at the ends -------------------------------------

    @Test
    fun edgeIndicatorsHiddenAtTheEndsOfTheTree() {
        val oldest = DateSegment("2022-12-31", DatePickerLevel.YEAR)
        val newest = DateSegment("2026-12-31", DatePickerLevel.YEAR)
        assertFalse(hasAdjacentSegment(dates, oldest, SegmentSide.LEFT))
        assertTrue(hasAdjacentSegment(dates, oldest, SegmentSide.RIGHT))
        assertTrue(hasAdjacentSegment(dates, newest, SegmentSide.LEFT))
        assertFalse(hasAdjacentSegment(dates, newest, SegmentSide.RIGHT))
    }

    @Test
    fun adjacentIsNullAtTheEndsOfTheTree() {
        val oldest = DateSegment("2022-12-31", DatePickerLevel.YEAR)
        assertNull(adjacentSegment(dates, oldest, SegmentSide.LEFT))
    }

    // --- swipe mapping -----------------------------------------------------------

    @Test
    fun rightwardSwipeGoesToPreviousLeftwardSwipeGoesToNext() {
        val current = DateSegment("2024-12-31", DatePickerLevel.YEAR)
        // A rightward swipe reveals the older (left) segment.
        assertEquals(
            DateSegment("2022-12-31", DatePickerLevel.YEAR),
            segmentForSwipe(dates, current, swipeRightward = true),
        )
        // A leftward swipe reveals the newer (right) segment.
        assertEquals(
            DateSegment("2026-12-31", DatePickerLevel.YEAR),
            segmentForSwipe(dates, current, swipeRightward = false),
        )
    }

    @Test
    fun swipeTowardAnAbsentSegmentIsANoOp() {
        val newest = DateSegment("2026-12-31", DatePickerLevel.YEAR)
        // Leftward swipe would go newer, but 2026 is the newest — no-op (null).
        assertNull(segmentForSwipe(dates, newest, swipeRightward = false))
    }
}

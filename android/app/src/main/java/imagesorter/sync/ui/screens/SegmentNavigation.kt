package imagesorter.sync.ui.screens

import imagesorter.sync.data.api.dto.MediaDatesDto

/**
 * Which side of an active segment a horizontal swipe (or an edge indicator) points at.
 * [LEFT] is the older/previous segment, [RIGHT] the newer/next one — matching the timeline's
 * newest-first order and the left/right edge indicators.
 */
enum class SegmentSide { LEFT, RIGHT }

/**
 * The ISO date prefix every capture date inside [segment] shares — the `datePrefix` that bounds
 * [imagesorter.sync.data.media.MediaRepository.segmentTimeline]. The segment's own
 * [DateSegment.date] (its latest day) is the `fromDate` page start; this is the prefix all in-segment
 * dates begin with: `"2024-"` for a year, `"2024-03-"` for a month, the full date for a day.
 */
fun segmentDatePrefix(segment: DateSegment): String = when (segment.granularity) {
    DatePickerLevel.YEAR -> segment.date.take(4) + "-"
    DatePickerLevel.MONTH -> segment.date.take(7) + "-"
    DatePickerLevel.DAY -> segment.date
}

/**
 * All segments at [level] that actually contain photos, oldest-first. Drawn only from the
 * available-dates [dates] tree — which lists only non-empty buckets — so adjacency over this list
 * can never land on an empty segment. Dates resolve the same way the picker confirms: a year to
 * its Dec 31, a month to its last calendar day, a day to itself.
 */
fun segmentsAtLevel(dates: MediaDatesDto, level: DatePickerLevel): List<DateSegment> = when (level) {
    DatePickerLevel.YEAR -> dates.keys.sorted().map { yearSegment(it) }
    DatePickerLevel.MONTH -> dates.keys.sorted().flatMap { year ->
        dates[year].orEmpty().keys.sorted().map { month -> monthSegment(year, month) }
    }
    DatePickerLevel.DAY -> dates.keys.sorted().flatMap { year ->
        dates[year].orEmpty().entries.sortedBy { it.key }.flatMap { (month, days) ->
            days.sorted().map { day -> daySegment(year, month, day) }
        }
    }
}

/**
 * The nearest non-empty segment adjacent to [current] on [side], at the same granularity, resolved
 * against the [dates] tree; null at the ends of the tree (or if [current] is not in it). Because
 * [segmentsAtLevel] contains only buckets with photos, the immediate neighbour is already the
 * nearest one that actually has photos — gaps (empty years/months) are skipped for free.
 */
fun adjacentSegment(dates: MediaDatesDto, current: DateSegment, side: SegmentSide): DateSegment? {
    val all = segmentsAtLevel(dates, current.granularity)
    val index = all.indexOfFirst { segmentKey(it) == segmentKey(current) }
    if (index < 0) return null
    val neighbour = when (side) {
        SegmentSide.LEFT -> index - 1
        SegmentSide.RIGHT -> index + 1
    }
    return all.getOrNull(neighbour)
}

/** Whether an edge indicator should show on [side] — i.e. an adjacent segment exists there. */
fun hasAdjacentSegment(dates: MediaDatesDto, current: DateSegment, side: SegmentSide): Boolean =
    adjacentSegment(dates, current, side) != null

/**
 * The segment a horizontal swipe should navigate to, or null to stay put (no adjacent segment that
 * way — the swipe is a no-op). A rightward swipe reveals the older, left-side segment; a leftward
 * swipe reveals the newer, right-side segment.
 */
fun segmentForSwipe(dates: MediaDatesDto, current: DateSegment, swipeRightward: Boolean): DateSegment? {
    val side = if (swipeRightward) SegmentSide.LEFT else SegmentSide.RIGHT
    return adjacentSegment(dates, current, side)
}

private fun yearSegment(year: String): DateSegment =
    DateSegment("$year-12-31", DatePickerLevel.YEAR)

private fun monthSegment(year: String, month: String): DateSegment {
    val lastDay = java.time.YearMonth.of(year.toInt(), month.toInt()).lengthOfMonth()
    return DateSegment("$year-$month-$lastDay", DatePickerLevel.MONTH)
}

private fun daySegment(year: String, month: String, day: Int): DateSegment =
    DateSegment("$year-$month-${day.toString().padStart(2, '0')}", DatePickerLevel.DAY)

/** The tree bucket key identifying [segment] at its granularity (year / year-month / full date). */
private fun segmentKey(segment: DateSegment): String = when (segment.granularity) {
    DatePickerLevel.YEAR -> segment.date.take(4)
    DatePickerLevel.MONTH -> segment.date.take(7)
    DatePickerLevel.DAY -> segment.date.take(10)
}

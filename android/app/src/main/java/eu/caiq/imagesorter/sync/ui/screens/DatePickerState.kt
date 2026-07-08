package eu.caiq.imagesorter.sync.ui.screens

import eu.caiq.imagesorter.sync.data.api.dto.MediaDatesDto

/** Which granularity the date picker is currently showing tiles for. */
enum class DatePickerLevel { YEAR, MONTH, DAY }

/**
 * A confirmed Go-To-Date selection: the resolved ISO [date] plus the [granularity]
 * (year / month / day) the user drilled to. Segment navigation uses the granularity
 * to decide which slice of the timeline to present and how to step to adjacent segments.
 */
data class DateSegment(val date: String, val granularity: DatePickerLevel)

/** Full month names indexed by 1-based month number; [MONTH_NAMES][1] == "January". */
internal val MONTH_NAMES = listOf(
    "", "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/**
 * One tappable crumb in the picker breadcrumb: its display [label] and the [level]
 * tapping it returns the picker to.
 */
data class BreadcrumbSegment(val label: String, val level: DatePickerLevel)

/**
 * Immutable navigation state for the Go-To-Date picker. Drilling down ([selectYear],
 * [selectMonth]) and backing out ([up]) return new instances; the current [level] is
 * inferred from which of [year]/[month] are set. [tiles] are the labels to render at
 * the current level, drawn only from the available [dates] tree (empty buckets are
 * absent from that tree, so they never appear).
 */
data class DatePickerState(
    private val dates: MediaDatesDto,
    val year: String? = null,
    val month: String? = null,
    val day: String? = null,
) {
    val level: DatePickerLevel = when {
        year == null -> DatePickerLevel.YEAR
        month == null -> DatePickerLevel.MONTH
        else -> DatePickerLevel.DAY
    }

    /** Labels to render at the current level, newest-first. */
    val tiles: List<String> = when (level) {
        DatePickerLevel.YEAR -> dates.keys.sortedDescending()
        DatePickerLevel.MONTH -> dates[year].orEmpty().keys.sorted()
        DatePickerLevel.DAY -> dates[year]?.get(month).orEmpty().sorted().map { it.toString() }
    }

    /**
     * Trail of selected segments above the current level. Each crumb's level is the
     * destination it returns to when tapped (the year crumb reopens that year's month
     * list, the month crumb that month's day list).
     */
    val breadcrumb: List<BreadcrumbSegment> = buildList {
        year?.let { add(BreadcrumbSegment(it, DatePickerLevel.MONTH)) }
        month?.let { add(BreadcrumbSegment(MONTH_NAMES[it.toInt()], DatePickerLevel.DAY)) }
    }

    /** Drill into [year]'s months. */
    fun selectYear(year: String): DatePickerState = copy(year = year, month = null, day = null)

    /** Drill into [month]'s days (only valid from the month level). */
    fun selectMonth(month: String): DatePickerState = copy(month = month, day = null)

    /** Pick a specific [day]; stays at the day level but records the choice for Confirm. */
    fun selectDay(day: String): DatePickerState = copy(day = day)

    /** Tap a tile [label] at the current level: drills year → month → day accordingly. */
    fun selectDeeper(label: String): DatePickerState = when (level) {
        DatePickerLevel.YEAR -> selectYear(label)
        DatePickerLevel.MONTH -> selectMonth(label)
        DatePickerLevel.DAY -> selectDay(label)
    }

    /** Return the picker to [level], discarding the selections below it. */
    fun goTo(level: DatePickerLevel): DatePickerState = when (level) {
        DatePickerLevel.YEAR -> copy(year = null, month = null, day = null)
        DatePickerLevel.MONTH -> copy(month = null, day = null)
        DatePickerLevel.DAY -> copy(day = null)
    }

    /**
     * The ISO date string a Confirm tap seeks to, at the deepest current selection:
     * a year confirms to its Dec 31, a month to its last calendar day, a day to itself.
     * Null when nothing is selected yet (the year-listing level has no target).
     */
    fun confirmedDate(): String? = when {
        year == null -> null
        month == null -> "$year-12-31"
        day == null -> {
            val lastDay = java.time.YearMonth.of(year.toInt(), month.toInt()).lengthOfMonth()
            "$year-$month-$lastDay"
        }
        else -> "$year-$month-${day.toInt().toString().padStart(2, '0')}"
    }

    /**
     * The confirmed segment at the deepest current selection: the resolved [confirmedDate]
     * paired with the granularity the user drilled to (a year-only pick is YEAR, year+month
     * is MONTH, a full date is DAY). Null when nothing is selected yet. Distinct from [level],
     * which is the level currently *listing* tiles (one deeper than the last committed pick).
     */
    fun confirmedSegment(): DateSegment? {
        val date = confirmedDate() ?: return null
        val granularity = when {
            month == null -> DatePickerLevel.YEAR
            day == null -> DatePickerLevel.MONTH
            else -> DatePickerLevel.DAY
        }
        return DateSegment(date, granularity)
    }

    /**
     * Go up one level (back gesture): day → month, month → year. At the year level
     * there is no parent, so returns null to signal the modal should dismiss.
     */
    fun up(): DatePickerState? = when (level) {
        DatePickerLevel.YEAR -> null
        DatePickerLevel.MONTH -> copy(year = null, month = null, day = null)
        DatePickerLevel.DAY -> copy(month = null, day = null)
    }
}

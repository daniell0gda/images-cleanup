package imagesorter.sync.ui

import imagesorter.sync.ui.screens.BreadcrumbSegment
import imagesorter.sync.ui.screens.DateSegment
import imagesorter.sync.ui.screens.DatePickerLevel
import imagesorter.sync.ui.screens.DatePickerState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure state-machine driving the Go-To-Date picker modal: level navigation,
 * breadcrumb construction and the Confirm date string at each granularity. Kept
 * free of Compose so the navigation logic is unit-tested directly (§ plan: the
 * modal is a stateless Composable driven by this holder).
 */
class GoToDateStateTest {

    private val dates = mapOf(
        "2024" to mapOf(
            "03" to listOf(1, 5, 12),
            "12" to listOf(24, 25),
        ),
        "2022" to mapOf(
            "07" to listOf(8),
        ),
    )

    @Test
    fun yearTilesShowOnlyYearsPresentNewestFirst() {
        val state = DatePickerState(dates)
        assertEquals(DatePickerLevel.YEAR, state.level)
        assertEquals(listOf("2024", "2022"), state.tiles)
    }

    @Test
    fun selectingAYearShowsThatYearsMonthTilesOnly() {
        val state = DatePickerState(dates).selectYear("2024")
        assertEquals(DatePickerLevel.MONTH, state.level)
        assertEquals(listOf("03", "12"), state.tiles)
    }

    @Test
    fun selectingAMonthShowsThatMonthsDayTilesOnly() {
        val state = DatePickerState(dates).selectYear("2024").selectMonth("03")
        assertEquals(DatePickerLevel.DAY, state.level)
        assertEquals(listOf("1", "5", "12"), state.tiles)
    }

    @Test
    fun breadcrumbReflectsTheCurrentPathAtEachLevel() {
        // Each crumb's level is the destination it returns to when tapped: the year
        // crumb returns to that year's month list, the month crumb to its day list.
        val year = DatePickerState(dates).selectYear("2024")
        assertEquals(
            listOf(BreadcrumbSegment("2024", DatePickerLevel.MONTH)),
            year.breadcrumb,
        )

        val month = year.selectMonth("03")
        assertEquals(
            listOf(
                BreadcrumbSegment("2024", DatePickerLevel.MONTH),
                BreadcrumbSegment("March", DatePickerLevel.DAY),
            ),
            month.breadcrumb,
        )
    }

    @Test
    fun tappingABreadcrumbSegmentJumpsBackToThatLevel() {
        val day = DatePickerState(dates).selectYear("2024").selectMonth("03").selectDay("5")
        assertEquals(DatePickerLevel.DAY, day.level)

        // Tapping the year crumb (destination MONTH) returns to 2024's month list.
        val backToMonth = day.goTo(DatePickerLevel.MONTH)
        assertEquals(DatePickerLevel.MONTH, backToMonth.level)
        assertEquals("2024", backToMonth.year)
        assertEquals(listOf("03", "12"), backToMonth.tiles)
    }

    @Test
    fun backGoesUpOneLevelAndDismissesAtTheYearLevel() {
        val day = DatePickerState(dates).selectYear("2024").selectMonth("03")
        // Day level → up to month level.
        val month = day.up()
        assertEquals(DatePickerLevel.MONTH, month?.level)
        // Month level → up to year level.
        val year = month?.up()
        assertEquals(DatePickerLevel.YEAR, year?.level)
        // Year level → no parent, so back dismisses the modal (null).
        assertEquals(null, year?.up())
    }

    @Test
    fun confirmAtYearLevelSeeksToDecemberThirtyFirst() {
        val state = DatePickerState(dates).selectYear("2024")
        assertEquals("2024-12-31", state.confirmedDate())
    }

    @Test
    fun confirmAtMonthLevelSeeksToTheLastDayOfThatMonth() {
        val march = DatePickerState(dates).selectYear("2024").selectMonth("03")
        assertEquals("2024-03-31", march.confirmedDate())

        // February of a leap year resolves to the 29th, not a fixed 28/30/31.
        val febLeap = DatePickerState(
            mapOf("2024" to mapOf("02" to listOf(1))),
        ).selectYear("2024").selectMonth("02")
        assertEquals("2024-02-29", febLeap.confirmedDate())

        // A 30-day month resolves to the 30th.
        val april = DatePickerState(
            mapOf("2023" to mapOf("04" to listOf(1))),
        ).selectYear("2023").selectMonth("04")
        assertEquals("2023-04-30", april.confirmedDate())
    }

    @Test
    fun confirmAtDayLevelSeeksToThatExactZeroPaddedDay() {
        val state = DatePickerState(dates).selectYear("2024").selectMonth("03").selectDay("5")
        assertEquals("2024-03-05", state.confirmedDate())
    }

    @Test
    fun confirmedSegmentReportsDateAndGranularityAtDeepestLevel() {
        // Year-only selection confirms as a YEAR segment.
        val year = DatePickerState(dates).selectYear("2024")
        assertEquals(DateSegment("2024-12-31", DatePickerLevel.YEAR), year.confirmedSegment())

        // Year + month confirms as a MONTH segment.
        val month = year.selectMonth("03")
        assertEquals(DateSegment("2024-03-31", DatePickerLevel.MONTH), month.confirmedSegment())

        // Full date confirms as a DAY segment.
        val day = month.selectDay("5")
        assertEquals(DateSegment("2024-03-05", DatePickerLevel.DAY), day.confirmedSegment())
    }

    @Test
    fun confirmedSegmentIsNullBeforeAnyYearIsSelected() {
        assertEquals(null, DatePickerState(dates).confirmedSegment())
    }
}

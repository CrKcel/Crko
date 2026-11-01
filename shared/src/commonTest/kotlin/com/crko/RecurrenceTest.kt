package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.OrdinalWeekday
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.matchesRule
import com.crko.domain.occurrenceOrdinal
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecurrenceTest {

    // 2026-10-27 is a Tuesday.
    private val anchor = LocalDate(2026, 10, 27)

    private fun event(
        repeat: RepeatRule? = null,
        excludedDates: List<String> = emptyList(),
    ) = Event(
        id = "e1",
        title = "Event",
        date = anchor.toString(),
        startMinutes = 9 * 60,
        endMinutes = 10 * 60,
        repeat = repeat,
        excludedDates = excludedDates,
    )

    @Test
    fun oneOffEventOnlyMatchesItsOwnDate() {
        val event = event()
        assertTrue(event.occursOn(anchor))
        assertFalse(event.occursOn(anchor.plusDays(1)))
        assertFalse(event.occursOn(anchor.plusDays(-1)))
    }

    @Test
    fun dailyRepeatSkipsDatesBeforeTheAnchor() {
        val event = event(RepeatRule(RepeatFrequency.Daily))
        assertFalse(event.occursOn(anchor.plusDays(-1)))
        assertTrue(event.occursOn(anchor))
        assertTrue(event.occursOn(anchor.plusDays(100)))
    }

    @Test
    fun weeklyRepeatMatchesTheAnchorWeekday() {
        val event = event(RepeatRule(RepeatFrequency.Weekly))
        assertFalse(event.occursOn(anchor.plusDays(1)))
        assertTrue(event.occursOn(anchor.plusDays(7)))
        assertFalse(event.occursOn(anchor.plusDays(8)))
    }

    @Test
    fun customRepeatMatchesOnlySelectedWeekdays() {
        // Monday and Thursday.
        val event = event(RepeatRule(RepeatFrequency.Custom, listOf(0, 3)))
        assertFalse(event.occursOn(anchor.plusDays(1)))
        assertTrue(event.occursOn(anchor.plusDays(2)))
        assertTrue(event.occursOn(anchor.plusDays(6)))
    }

    @Test
    fun excludedDatesAreSkipped() {
        val event = event(RepeatRule(RepeatFrequency.Daily), excludedDates = listOf(anchor.plusDays(1).toString()))
        assertTrue(event.occursOn(anchor))
        assertFalse(event.occursOn(anchor.plusDays(1)))
        assertTrue(event.occursOn(anchor.plusDays(2)))
    }

    @Test
    fun dailyCountStopsTheSeries() {
        val event = event(RepeatRule(RepeatFrequency.Daily, count = 3))
        assertTrue(event.occursOn(anchor))
        assertTrue(event.occursOn(anchor.plusDays(2)))
        assertFalse(event.occursOn(anchor.plusDays(3)))
    }

    @Test
    fun countIncludesTheStartEvenWhenItMissesByDay() {
        // 2026-10-27 is a Tuesday; the rule only names Fridays.
        val event = event(RepeatRule(RepeatFrequency.Custom, listOf(4), count = 2))
        assertTrue(event.occursOn(LocalDate(2026, 10, 30)))
        assertFalse(event.occursOn(LocalDate(2026, 11, 6)))
    }

    @Test
    fun monthlyCountStartsAtDtstartEvenWhenTheDayDoesNotMatch() {
        val event = event(
            RepeatRule(
                frequency = RepeatFrequency.Monthly,
                byMonthDay = listOf(1, 15),
                count = 3,
            ),
        )
        assertTrue(event.occursOn(anchor))
        assertTrue(event.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 15)))
        assertFalse(event.occursOn(LocalDate(2026, 12, 1)))
    }

    @Test
    fun dailyIntervalSkipsDays() {
        val event = event(RepeatRule(RepeatFrequency.Daily, interval = 2))
        assertTrue(event.occursOn(anchor))
        assertFalse(event.occursOn(anchor.plusDays(1)))
        assertTrue(event.occursOn(anchor.plusDays(2)))
    }

    @Test
    fun monthlyIntervalSkipsMonths() {
        val event = event(RepeatRule(RepeatFrequency.Monthly, interval = 2))
        assertTrue(event.occursOn(anchor))
        assertFalse(event.occursOn(LocalDate(2026, 11, 27)))
        assertTrue(event.occursOn(LocalDate(2026, 12, 27)))
        assertTrue(event.occursOn(LocalDate(2027, 2, 27)))
    }

    @Test
    fun monthlyOrdinalWeekdayFindsTheLastFriday() {
        // 2026-10-30 is the last Friday of October.
        val friday = LocalDate(2026, 10, 30)
        val event = Event(
            id = "e1",
            title = "Retro",
            date = friday.toString(),
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Monthly,
                byDay = listOf(OrdinalWeekday(-1, 4)),
            ),
        )
        assertTrue(event.occursOn(friday))
        assertTrue(event.occursOn(LocalDate(2026, 11, 27)))
        assertTrue(event.occursOn(LocalDate(2026, 12, 25)))
        assertFalse(event.occursOn(LocalDate(2026, 12, 18)))
    }

    @Test
    fun monthlySetPositionPicksTheLastWeekday() {
        val friday = LocalDate(2026, 10, 30)
        val event = Event(
            id = "e1",
            title = "Report",
            date = friday.toString(),
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Monthly,
                weekdays = listOf(0, 1, 2, 3, 4),
                bySetPos = listOf(-1),
            ),
        )
        assertTrue(event.occursOn(friday))
        assertTrue(event.occursOn(LocalDate(2026, 11, 30)))
        assertFalse(event.occursOn(LocalDate(2026, 11, 27)))
    }

    @Test
    fun monthlyByMonthDayMatchesBothDays() {
        val event = event(
            RepeatRule(
                frequency = RepeatFrequency.Monthly,
                byMonthDay = listOf(1, 15),
            ),
        )
        // DTSTART itself is always part of the set.
        assertTrue(event.occursOn(anchor))
        assertTrue(event.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 15)))
        assertFalse(event.occursOn(LocalDate(2026, 11, 16)))
    }

    @Test
    fun yearlyByMonthAndMonthDay() {
        val event = event(
            RepeatRule(
                frequency = RepeatFrequency.Yearly,
                byMonth = listOf(10),
                byMonthDay = listOf(27),
            ),
        )
        assertTrue(event.occursOn(anchor))
        assertTrue(event.occursOn(LocalDate(2027, 10, 27)))
        assertFalse(event.occursOn(LocalDate(2027, 10, 28)))
    }

    @Test
    fun rdateAddsAnOccurrenceOutsideTheRule() {
        val event = event(
            RepeatRule(
                frequency = RepeatFrequency.Daily,
                until = anchor.plusDays(1).toString(),
                rDates = listOf(LocalDate(2026, 11, 5).toString()),
            ),
        )
        assertTrue(event.occursOn(anchor.plusDays(1)))
        assertFalse(event.occursOn(anchor.plusDays(2)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 5)))
    }

    @Test
    fun excludedOccurrenceStartSkipsTheWholeMultiDaySpan() {
        val event = Event(
            id = "e1",
            title = "Trip",
            date = anchor.toString(),
            startMinutes = 0,
            endMinutes = 0,
            endDate = anchor.plusDays(2).toString(),
            allDay = true,
            repeat = RepeatRule(RepeatFrequency.Weekly),
        )
        assertTrue(event.occursOn(anchor.plusDays(2)))
        assertTrue(event.occursOn(anchor.plusDays(7)))
        assertTrue(event.occursOn(anchor.plusDays(9)))
        assertFalse(event.occursOn(anchor.plusDays(6)))
        val skipped = event.copy(excludedDates = listOf(anchor.plusDays(7).toString()))
        assertFalse(skipped.occursOn(anchor.plusDays(7)))
        assertFalse(skipped.occursOn(anchor.plusDays(8)))
        assertTrue(skipped.occursOn(anchor.plusDays(14)))
    }

    @Test
    fun monthlyByMonthDayAndByDayLimitEachOther() {
        // 2026-11-13 is a Friday, 2026-12-13 is a Sunday.
        val event = Event(
            id = "f13",
            title = "Friday 13",
            date = "2026-10-13",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Monthly,
                weekdays = listOf(4),
                byMonthDay = listOf(13),
            ),
        )
        assertTrue(event.occursOn(LocalDate(2026, 11, 13)))
        assertFalse(event.occursOn(LocalDate(2026, 12, 13)))
    }

    @Test
    fun monthlyCountOnlyCountsMatchingMonths() {
        val event = Event(
            id = "jan",
            title = "January",
            date = "2026-01-15",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Monthly,
                byMonth = listOf(1),
                count = 2,
            ),
        )
        assertTrue(event.occursOn(LocalDate(2027, 1, 15)))
        assertFalse(event.occursOn(LocalDate(2026, 2, 15)))
    }

    @Test
    fun floatingUntilKeepsTheTimeOfDay() {
        val event = Event(
            id = "u",
            title = "Late",
            date = "2026-10-25",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            repeat = RepeatRule(RepeatFrequency.Daily, until = "2026-10-27T09:00"),
        )
        assertTrue(event.occursOn(LocalDate(2026, 10, 26)))
        assertFalse(event.occursOn(LocalDate(2026, 10, 27)))
    }

    @Test
    fun occurrenceStartOnFindsTheFirstDayOfAMultiDaySeries() {
        val event = Event(
            id = "trip",
            title = "Trip",
            date = anchor.toString(),
            startMinutes = 0,
            endMinutes = 0,
            endDate = anchor.plusDays(2).toString(),
            allDay = true,
            repeat = RepeatRule(RepeatFrequency.Weekly),
        )
        assertEquals(anchor, event.occurrenceStartOn(anchor.plusDays(1)))
        assertEquals(anchor.plusDays(7), event.occurrenceStartOn(anchor.plusDays(7)))
        assertEquals(anchor.plusDays(7), event.occurrenceStartOn(anchor.plusDays(8)))
    }

    @Test
    fun weeklySetPosKeepsOnlyTheSelectedWeekday() {
        val event = event(
            RepeatRule(
                frequency = RepeatFrequency.Custom,
                weekdays = listOf(0, 1, 2, 3, 4),
                bySetPos = listOf(-1),
            ),
        )
        // 2026-10-27 is a Tuesday and stays as DTSTART; the rest of the week yields only Friday.
        assertTrue(event.occursOn(LocalDate(2026, 10, 27)))
        assertFalse(event.occursOn(LocalDate(2026, 10, 28)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 30)))
        assertFalse(event.occursOn(LocalDate(2026, 11, 5)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 6)))
    }

    @Test
    fun weeklyCountIgnoresDatesFilteredOutByMonth() {
        val event = Event(
            id = "march",
            title = "March Mondays",
            date = "2026-01-05",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Custom,
                weekdays = listOf(0),
                byMonth = listOf(3),
                count = 3,
            ),
        )
        // DTSTART is the first occurrence; only the first two March Mondays remain.
        assertTrue(event.occursOn(LocalDate(2026, 3, 2)))
        assertTrue(event.occursOn(LocalDate(2026, 3, 9)))
        assertFalse(event.occursOn(LocalDate(2026, 3, 16)))
    }

    @Test
    fun byWeekNoHonoursASundayWeekStart() {
        val event = Event(
            id = "week53",
            title = "Week 1",
            date = "2024-12-29",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Yearly,
                byWeekNo = listOf(1, -1),
                weekStart = 6,
            ),
        )
        assertTrue(event.occursOn(LocalDate(2024, 12, 29)))
        assertFalse(event.occursOn(LocalDate(2025, 1, 5)))
        assertFalse(event.occursOn(LocalDate(2025, 12, 21)))
        assertTrue(event.occursOn(LocalDate(2025, 12, 28)))
    }

    @Test
    fun byWeekNoFindsWeekOneStartingInThePreviousYear() {
        val event = Event(
            id = "week-one",
            title = "Week one",
            date = "2024-01-01",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Yearly,
                weekdays = listOf(6),
                byWeekNo = listOf(1),
                weekStart = 6,
            ),
        )
        assertTrue(event.occursOn(LocalDate(2024, 12, 29)))
        assertFalse(event.occursOn(LocalDate(2024, 12, 22)))
    }

    @Test
    fun byWeekNoCountIncludesAWeekOneStartingInThePreviousYear() {
        val event = Event(
            id = "week-one-count",
            title = "Week one",
            date = "2024-01-01",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Yearly,
                weekdays = listOf(6),
                byWeekNo = listOf(1),
                weekStart = 6,
                count = 2,
            ),
        )
        assertTrue(event.occursOn(LocalDate(2024, 12, 29)))
        assertFalse(event.occursOn(LocalDate(2025, 12, 28)))
    }

    @Test
    fun repeatRuleSurvivesJsonRoundTrip() {
        val state = AppState(
            events = listOf(
                event(
                    RepeatRule(RepeatFrequency.Custom, listOf(0, 3)),
                    excludedDates = listOf(anchor.plusDays(2).toString()),
                ),
            ),
        )
        val decoded = AppJson.decodeFromString(
            AppState.serializer(),
            AppJson.encodeToString(AppState.serializer(), state),
        )
        assertEquals(state, decoded)
        assertFalse(decoded.events.single().occursOn(anchor.plusDays(2)))
    }

    @Test
    fun weeklyOrdinalMatchesABruteForceCount() {
        val rules = listOf(
            RepeatRule(RepeatFrequency.Weekly),
            RepeatRule(RepeatFrequency.Weekly, interval = 2),
            RepeatRule(RepeatFrequency.Custom, weekdays = listOf(0, 2, 4)),
            RepeatRule(RepeatFrequency.Custom, weekdays = listOf(0, 2, 4), interval = 2),
            RepeatRule(RepeatFrequency.Custom, weekdays = listOf(5, 6), interval = 4),
            RepeatRule(RepeatFrequency.Weekly, weekStart = 2, interval = 2),
            RepeatRule(RepeatFrequency.Custom, weekdays = listOf(1), weekStart = 4, interval = 3),
            // Non-uniform weekly rules must keep using the scan, with the same answers.
            RepeatRule(RepeatFrequency.Weekly, interval = 2, byMonth = listOf(1, 2)),
            RepeatRule(RepeatFrequency.Custom, weekdays = listOf(0, 4), bySetPos = listOf(-1)),
            RepeatRule(RepeatFrequency.Daily),
            RepeatRule(RepeatFrequency.Daily, interval = 3, weekdays = listOf(0, 2)),
            RepeatRule(RepeatFrequency.Monthly, byDay = listOf(OrdinalWeekday(-1, 4))),
        )
        for (rule in rules) {
            // A decades-old anchor is exactly the case the old week-by-week scan made expensive.
            for (start in listOf(anchor, LocalDate(1971, 3, 15), LocalDate(2000, 3, 1))) {
                var day = start
                while (day <= start.plusDays(RANGE_DAYS)) {
                    assertEquals(
                        oracleOrdinal(rule, start, day),
                        occurrenceOrdinal(rule, start, day, null),
                        "$rule from $start on $day",
                    )
                    day = day.plusDays(1)
                }
            }
        }
    }

    /** Independent definition: DTSTART is occurrence one, then every matching day in order. */
    private fun oracleOrdinal(rule: RepeatRule, anchor: LocalDate, date: LocalDate): Int? {
        if (date < anchor) return null
        if (date == anchor) return 1
        var count = 1
        var cursor = anchor.plusDays(1)
        while (cursor <= date) {
            val matches = matchesRule(rule, anchor, cursor)
            if (matches) count++
            if (cursor == date) return if (matches) count else null
            cursor = cursor.plusDays(1)
        }
        return null
    }

    private companion object {
        /** Days after the anchor that every rule shape is checked over. */
        const val RANGE_DAYS = 90
    }
}

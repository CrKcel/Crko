package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.EndOfDayMinutes
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.OrdinalWeekday
import com.crko.domain.OccurrenceTime
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.matchesRule
import com.crko.domain.durationUntil
import com.crko.domain.isRuleOccurrence
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.occurrenceDate
import com.crko.domain.occurrenceRangesOn
import com.crko.domain.occurrenceStartsBetween
import com.crko.domain.occurrenceTimesOn
import com.crko.domain.occurrenceOrdinal
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.plusMonths
import com.crko.domain.plusYears
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlin.time.Duration.Companion.hours
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecurrenceTest {

    // 2026-10-27 is a Tuesday.
    private val anchor = LocalDate(2026, 10, 27)

    private fun days(vararg values: Int): List<OrdinalWeekday> = values.map { OrdinalWeekday(0, it) }

    private fun event(
        repeat: RepeatRule? = null,
        rdates: List<RecurrenceDate> = emptyList(),
        excludedDates: List<String> = emptyList(),
    ) = Event(
        id = "e1",
        title = "Event",
        start = IcsTimeValue.Floating(LocalDateTime(anchor, LocalTime(9, 0))),
        end = IcsTimeValue.Floating(LocalDateTime(anchor, LocalTime(10, 0))),
        recurrence = if (repeat == null && rdates.isEmpty() && excludedDates.isEmpty()) {
            null
        } else {
            Recurrence(
                rule = repeat,
                rdates = rdates,
                exdates = excludedDates.map { IcsTimeValue.Date(LocalDate.parse(it)) },
            )
        },
    )

    @Test
    fun oneOffEventOnlyMatchesItsOwnDate() {
        val event = event()
        assertTrue(event.occursOn(anchor))
        assertFalse(event.occursOn(anchor.plusDays(1)))
        assertFalse(event.occursOn(anchor.plusDays(-1)))
    }

    @Test
    fun aSeriesAnchoredOnLeapDayOnlyFiresInLeapYears() {
        fun leapEvent(repeat: RepeatRule) = Event(
            id = "leap",
            title = "Leap",
            start = IcsTimeValue.Floating(LocalDateTime(LocalDate(2028, 2, 29), LocalTime(9, 0))),
            end = IcsTimeValue.Floating(LocalDateTime(LocalDate(2028, 2, 29), LocalTime(10, 0))),
            recurrence = Recurrence(rule = repeat),
        )
        // Feb 29 anchors are excluded from the yearly arithmetic path on purpose; the period walk
        // must keep them in leap years only, with COUNT spanning the four-year gaps.
        val yearly = leapEvent(RepeatRule(RepeatFrequency.Yearly, count = 3))
        assertTrue(yearly.occursOn(LocalDate(2028, 2, 29)))
        assertTrue(yearly.occursOn(LocalDate(2032, 2, 29)))
        assertTrue(yearly.occursOn(LocalDate(2036, 2, 29)))
        assertFalse(yearly.occursOn(LocalDate(2029, 2, 28)))
        assertFalse(yearly.occursOn(LocalDate(2031, 2, 28)))
        assertFalse(yearly.occursOn(LocalDate(2040, 2, 29)))

        // Monthly candidates clamp to real days: a short February has no 29th to invent,
        // and a long month keeps the anchor's day-of-month.
        val monthly = leapEvent(RepeatRule(RepeatFrequency.Monthly))
        assertTrue(monthly.occursOn(LocalDate(2028, 3, 29)))
        assertTrue(monthly.occursOn(LocalDate(2032, 2, 29)))
        assertFalse(monthly.occursOn(LocalDate(2029, 2, 28)))
        assertFalse(monthly.occursOn(LocalDate(2029, 3, 28)))
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
        val event = event(RepeatRule(RepeatFrequency.Weekly, byDay = days(0, 3)))
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
        val event = event(RepeatRule(RepeatFrequency.Weekly, byDay = days(4), count = 2))
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
        val event = testEvent(
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
        val event = testEvent(
            id = "e1",
            title = "Report",
            date = friday.toString(),
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Monthly,
                byDay = days(0, 1, 2, 3, 4),
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
        assertTrue(event.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 15)))
        // Adjacent days must not leak into the set.
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
                until = IcsTimeValue.Date(anchor.plusDays(1)),
            ),
            rdates = listOf(RecurrenceDate(IcsTimeValue.Date(LocalDate(2026, 11, 5)))),
        )
        assertTrue(event.occursOn(anchor.plusDays(1)))
        assertFalse(event.occursOn(anchor.plusDays(2)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 5)))
    }

    @Test
    fun excludedOccurrenceStartSkipsTheWholeMultiDaySpan() {
        val event = testEvent(
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
        val skipped = event.copy(
            recurrence = event.recurrence?.copy(
                exdates = listOf(IcsTimeValue.Date(anchor.plusDays(7))),
            ),
        )
        assertFalse(skipped.occursOn(anchor.plusDays(7)))
        assertFalse(skipped.occursOn(anchor.plusDays(8)))
        assertTrue(skipped.occursOn(anchor.plusDays(14)))
    }

    @Test
    fun monthlyByMonthDayAndByDayLimitEachOther() {
        // 2026-11-13 is a Friday, 2026-12-13 is a Sunday.
        val event = testEvent(
            id = "f13",
            title = "Friday 13",
            date = "2026-10-13",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Monthly,
                byDay = days(4),
                byMonthDay = listOf(13),
            ),
        )
        assertTrue(event.occursOn(LocalDate(2026, 11, 13)))
        assertFalse(event.occursOn(LocalDate(2026, 12, 13)))
    }

    @Test
    fun monthlyCountOnlyCountsMatchingMonths() {
        val event = testEvent(
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
        val event = testEvent(
            id = "u",
            title = "Late",
            date = "2026-10-25",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            repeat = RepeatRule(
                RepeatFrequency.Daily,
                until = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 9, 0)),
            ),
        )
        assertTrue(event.occursOn(LocalDate(2026, 10, 26)))
        assertFalse(event.occursOn(LocalDate(2026, 10, 27)))
    }

    @Test
    fun occurrenceStartOnFindsTheFirstDayOfAMultiDaySeries() {
        val event = testEvent(
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
                frequency = RepeatFrequency.Weekly,
                byDay = days(0, 1, 2, 3, 4),
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
        val event = testEvent(
            id = "march",
            title = "March Mondays",
            date = "2026-01-05",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Weekly,
                byDay = days(0),
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
        val event = testEvent(
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
        val event = testEvent(
            id = "week-one",
            title = "Week one",
            date = "2024-01-01",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Yearly,
                byDay = days(6),
                byWeekNo = listOf(1),
                weekStart = 6,
            ),
        )
        assertTrue(event.occursOn(LocalDate(2024, 12, 29)))
        assertFalse(event.occursOn(LocalDate(2024, 12, 22)))
    }

    @Test
    fun byWeekNoCountIncludesAWeekOneStartingInThePreviousYear() {
        val event = testEvent(
            id = "week-one-count",
            title = "Week one",
            date = "2024-01-01",
            startMinutes = 540,
            endMinutes = 600,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Yearly,
                byDay = days(6),
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
            calendars = listOf(
                com.crko.domain.Calendar.local().copy(
                    events = listOf(
                        event(
                            RepeatRule(RepeatFrequency.Weekly, byDay = days(0, 3)),
                            excludedDates = listOf(anchor.plusDays(2).toString()),
                        ),
                    ),
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
            RepeatRule(RepeatFrequency.Weekly, byDay = days(0, 2, 4)),
            RepeatRule(RepeatFrequency.Weekly, byDay = days(0, 2, 4), interval = 2),
            RepeatRule(RepeatFrequency.Weekly, byDay = days(5, 6), interval = 4),
            RepeatRule(RepeatFrequency.Weekly, weekStart = 2, interval = 2),
            RepeatRule(RepeatFrequency.Weekly, byDay = days(1), weekStart = 4, interval = 3),
            // Non-uniform weekly rules must keep using the scan, with the same answers.
            RepeatRule(RepeatFrequency.Weekly, interval = 2, byMonth = listOf(1, 2)),
            RepeatRule(RepeatFrequency.Weekly, byDay = days(0, 4), bySetPos = listOf(-1)),
            RepeatRule(RepeatFrequency.Daily),
            RepeatRule(RepeatFrequency.Daily, interval = 3, byDay = days(0, 2)),
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

    /**
     * The plain monthly and yearly rules are answered by arithmetic instead of a scan; the same
     * dates have to come out of the period-by-period count they replaced.
     */
    @Test
    fun ordinalShortCutsAgreeWithThePeriodCountTheyReplace() {
        // Day-of-month <= 28 is exactly the range the monthly short cut claims to handle.
        val anchors = listOf(LocalDate(2024, 1, 5), LocalDate(2025, 8, 28), LocalDate(2026, 12, 14))
        var checked = 0
        anchors.forEach { start ->
            listOf(1, 2, 3, 6).forEach { interval ->
                listOf(
                    RepeatRule(RepeatFrequency.Monthly, interval = interval),
                    RepeatRule(RepeatFrequency.Yearly, interval = interval),
                ).forEach { rule ->
                    var date = start
                    repeat(if (rule.frequency == RepeatFrequency.Monthly) 40 else 14) {
                        date = when (rule.frequency) {
                            RepeatFrequency.Monthly -> date.plusMonths(1)
                            else -> date.plusYears(1)
                        }
                        assertEquals(
                            periodOrdinal(rule, start, date),
                            occurrenceOrdinal(rule, start, date, null),
                            "$rule from $start on $date",
                        )
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 200, "checked $checked dates")
    }

    /** Counts the matching periods one by one, the way the scanning branch does. */
    private fun periodOrdinal(rule: RepeatRule, anchor: LocalDate, date: LocalDate): Int? {
        if (!matchesRule(rule, anchor, date)) return null
        var ordinal = 1
        var step = 1
        while (true) {
            val candidate = when (rule.frequency) {
                RepeatFrequency.Monthly -> anchor.plusMonths(step)
                else -> LocalDate(anchor.year + step, anchor.month.number, anchor.day)
            }
            if (candidate > date) break
            if (matchesRule(rule, anchor, candidate)) ordinal++
            step++
        }
        return ordinal
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

    /**
     * The `COUNT` series of [rule] listed by an independent day-by-day walk from the anchor: the
     * set every cell query has to agree with, however the engine answers internally.
     */
    private fun oracleCountedDays(rule: RepeatRule, anchor: LocalDate, count: Int): Set<LocalDate> {
        val days = mutableSetOf(anchor)
        var ordinal = 1
        var cursor = anchor.plusDays(1)
        while (cursor <= LocalDate(2026, 12, 31)) {
            if (matchesRule(rule, anchor, cursor)) {
                ordinal++
                if (ordinal > count) break
                days += cursor
            }
            cursor = cursor.plusDays(1)
        }
        return days
    }

    @Test
    fun countLimitedMonthlyAndYearlySeriesAnswerLikeTheDayWalk() {
        val rules = listOf(
            RepeatRule(RepeatFrequency.Monthly, byMonthDay = listOf(1, 15), count = 4),
            RepeatRule(RepeatFrequency.Monthly, byDay = listOf(OrdinalWeekday(-1, 4)), count = 3),
            RepeatRule(RepeatFrequency.Yearly, byMonth = listOf(3), byMonthDay = listOf(14), count = 5),
            RepeatRule(RepeatFrequency.Yearly, byDay = days(6), byWeekNo = listOf(1), weekStart = 6, count = 2),
        )
        rules.forEach { rule ->
            // A decades-old anchor is exactly what made every cell query walk hundreds of periods.
            val start = LocalDate(1971, 3, 15)
            val record = testEvent(
                id = "old",
                title = "Old",
                date = start.toString(),
                startMinutes = 540,
                endMinutes = 600,
                repeat = rule,
            )
            val expected = oracleCountedDays(rule, start, rule.count ?: 0)
            var day = LocalDate(2026, 10, 1)
            while (day <= LocalDate(2026, 11, 30)) {
                assertEquals(day in expected, record.occursOn(day), "$rule on $day")
                day = day.plusDays(1)
            }
        }
    }

    @Test
    fun countIndexHonoursExdatesAndRdates() {
        val rule = RepeatRule(
            RepeatFrequency.Monthly,
            byMonthDay = listOf(1),
            count = 3,
        )
        val record = Event(
            id = "x",
            title = "X",
            start = IcsTimeValue.Floating(LocalDateTime(2026, 10, 1, 9, 0)),
            end = IcsTimeValue.Floating(LocalDateTime(2026, 10, 1, 10, 0)),
            recurrence = Recurrence(
                rule = rule,
                rdates = listOf(RecurrenceDate(IcsTimeValue.Date(LocalDate(2026, 11, 20)))),
                exdates = listOf(IcsTimeValue.Date(LocalDate(2026, 11, 1))),
            ),
        )
        assertTrue(record.occursOn(LocalDate(2026, 10, 1)))
        assertFalse(record.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(record.occursOn(LocalDate(2026, 12, 1)))
        assertFalse(record.occursOn(LocalDate(2027, 1, 1)))
        assertTrue(record.occursOn(LocalDate(2026, 11, 20)))
    }

    @Test
    fun utcUntilBoundsTheSeriesByTheEventsOwnInstantsNotTheDeviceClock() {
        // A New York 09:00 daily stopped with UNTIL=2026-11-01T08:00Z. The 11-01 instance starts
        // 14:00Z (EST), past the bound; folding its wall clock into a Shanghai device first
        // (01:00Z) would let one extra day through.
        val anchorValue = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "America/New_York")
        val rule = RepeatRule(
            RepeatFrequency.Daily,
            until = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-11-01T08:00:00Z")),
        )
        fun occurs(day: LocalDate) = isRuleOccurrence(
            anchor = LocalDate(2026, 10, 27),
            rule = rule,
            recurrence = Recurrence(rule = rule),
            candidate = day,
            startTime = OccurrenceTime(9 * 60, 0),
            anchorValue = anchorValue,
            zone = TimeZone.of("Asia/Shanghai"),
        )
        assertTrue(occurs(LocalDate(2026, 10, 31)))
        assertFalse(occurs(LocalDate(2026, 11, 1)))
    }

    @Test
    fun aFloatingSeriesLengthIsItsWallClockLengthAcrossDst() {
        // 2026-03-08 is New York's spring-forward day; the occurrence 02:30-03:30 exists on the
        // wall clock only. Folding the pair into instants in the device zone collapses the series
        // length to zero, while the wall clock the file names is one hour.
        val start = IcsTimeValue.Floating(LocalDateTime(2026, 3, 8, 2, 30))
        val end = IcsTimeValue.Floating(LocalDateTime(2026, 3, 8, 3, 30))
        assertEquals(1.hours, start.durationUntil(end, TimeZone.of("America/New_York")))
    }

    @Test
    fun byHourExpandsOneDayIntoSeveralStarts() {
        val event = testEvent(
            id = "rounds",
            title = "Rounds",
            date = anchor.toString(),
            startMinutes = 9 * 60,
            endMinutes = 10 * 60,
            repeat = RepeatRule(RepeatFrequency.Daily, byHour = listOf(9, 17)),
        )
        // Every rule day holds two starts, each keeping the anchor's one-hour length.
        assertEquals(
            listOf(9 * 60 to 10 * 60, 17 * 60 to 18 * 60),
            event.occurrenceRangesOn(anchor.plusDays(1)),
        )
        assertEquals(listOf(540, 1020), event.occurrenceTimesOn(anchor.plusDays(1)).map { it.minutes })
    }

    @Test
    fun byMinuteCombinesWithByHourAndFallsBackToTheAnchorTime() {
        val event = testEvent(
            id = "pulse",
            title = "Pulse",
            date = anchor.toString(),
            startMinutes = 9 * 60 + 30,
            endMinutes = 9 * 60 + 50,
            repeat = RepeatRule(RepeatFrequency.Daily, byHour = listOf(8), byMinute = listOf(0, 45)),
        )
        // BYHOUR replaces the anchor hour and BYMINUTE the minute; the length stays 20 minutes.
        assertEquals(
            listOf(8 * 60 to 8 * 60 + 20, 8 * 60 + 45 to 9 * 60 + 5),
            event.occurrenceRangesOn(anchor.plusDays(1)),
        )
    }

    @Test
    fun rdateDaysKeepTheSingleStoredStart() {
        val event = Event(
            id = "extra",
            title = "Extra",
            start = IcsTimeValue.Floating(LocalDateTime(anchor, LocalTime(9, 0))),
            end = IcsTimeValue.Floating(LocalDateTime(anchor, LocalTime(10, 0))),
            // The rule only fires on Tuesdays; the RDATE adds a Friday at the stored time.
            recurrence = Recurrence(
                rule = RepeatRule(
                    RepeatFrequency.Weekly,
                    byDay = days(1),
                    byHour = listOf(9, 17),
                ),
                rdates = listOf(RecurrenceDate(IcsTimeValue.Date(LocalDate(2026, 10, 30)))),
            ),
        )
        assertEquals(
            listOf(9 * 60 to 10 * 60, 17 * 60 to 18 * 60),
            event.occurrenceRangesOn(LocalDate(2026, 11, 3)),
        )
        assertEquals(listOf(9 * 60 to 10 * 60), event.occurrenceRangesOn(LocalDate(2026, 10, 30)))
    }

    @Test
    fun aDtstartThatMissesTheByFiltersKeepsItsOwnStart() {
        val event = testEvent(
            id = "anchor",
            title = "Anchor",
            date = anchor.toString(),
            startMinutes = 12 * 60,
            endMinutes = 13 * 60,
            repeat = RepeatRule(RepeatFrequency.Monthly, byMonthDay = listOf(1, 15), byHour = listOf(8)),
        )
        // DTSTART (the 27th) is still the first occurrence, unexpanded; the 1st expands to 08:00.
        assertEquals(listOf(12 * 60 to 13 * 60), event.occurrenceRangesOn(anchor))
        assertEquals(listOf(8 * 60 to 9 * 60), event.occurrenceRangesOn(LocalDate(2026, 11, 1)))
    }

    @Test
    fun byHourExpandsAcrossTheAnchorSpan() {
        // A two-day occurrence keeps covering the next day while that day also holds its own
        // starts; the tail slices of earlier instances must survive next to the day's own ones.
        val event = testEvent(
            id = "rounds",
            title = "Rounds",
            date = anchor.toString(),
            startMinutes = 9 * 60,
            endMinutes = 10 * 60,
            endDate = anchor.plusDays(1).toString(),
            repeat = RepeatRule(RepeatFrequency.Daily, byHour = listOf(9, 17)),
        )
        // The anchor day sits inside its own occurrence's span, so the earliest candidate start
        // would precede the anchor and the walk must clamp to the anchor instead of stopping.
        // Both instances run overnight, so each slice reaches the end of the day.
        assertEquals(
            listOf(9 * 60 to EndOfDayMinutes, 17 * 60 to EndOfDayMinutes),
            event.occurrenceRangesOn(anchor),
        )
        // The next day holds the tail slices of both anchor-day instances plus its own two
        // starts, which cover the day through midnight because the occurrence runs overnight.
        assertEquals(
            listOf(
                0 to 10 * 60,
                0 to 18 * 60,
                9 * 60 to EndOfDayMinutes,
                17 * 60 to EndOfDayMinutes,
            ),
            event.occurrenceRangesOn(anchor.plusDays(1)),
        )
    }

    private companion object {
        /** Days after the anchor that every rule shape is checked over. */
        const val RANGE_DAYS = 90
    }
}

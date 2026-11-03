package com.crko

import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.IcsTimeValue
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.state.event.EventNormalizer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventNormalizerTest {

    private val day = LocalDate(2026, 10, 27)

    private fun event(
        title: String = "Meeting",
        start: IcsTimeValue = IcsTimeValue.Date(day),
        end: IcsTimeValue? = IcsTimeValue.Date(day.plusDays(1)),
        recurrence: Recurrence? = null,
        status: EventStatus = EventStatus.Confirmed,
    ) = Event(
        id = null,
        title = title,
        start = start,
        end = end,
        recurrence = recurrence,
        status = status,
    )

    @Test
    fun blankTitlesAndInvalidRangesAreRejected() {
        assertNull(EventNormalizer.normalize(event(title = "   ")))
        assertNull(
            EventNormalizer.normalize(
                event(
                    start = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(10, 0))),
                    end = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(9, 0))),
                ),
            ),
        )
        // A date-only end cannot describe the end of a timed event.
        assertNull(
            EventNormalizer.normalize(
                event(
                    start = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(10, 0))),
                    end = IcsTimeValue.Date(day.plusDays(1)),
                ),
            ),
        )
    }

    @Test
    fun zeroDurationAndTrimmingSurviveNormalization() {
        val normalized = EventNormalizer.normalize(
            event(
                title = "  Focus  ",
                start = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(9, 0))),
                end = null,
            ),
        )
        assertNotNull(normalized)
        assertEquals("Focus", normalized.title)
        assertNull(normalized.end)
    }

    @Test
    fun anAllDayEventWithoutAnEndGetsAOneDaySpan() {
        val missing = EventNormalizer.normalize(event(end = null))
        assertNotNull(missing)
        assertEquals(IcsTimeValue.Date(day.plusDays(1)), missing.end)
        assertTrue(missing.occursOn(day))
        assertFalse(missing.occursOn(day.plusDays(1)))
    }

    @Test
    fun repeatCountersAreCoercedAndInstancesAreDeduplicated() {
        val normalized = EventNormalizer.normalize(
            event(
                start = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(9, 0))),
                end = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(10, 0))),
                recurrence = Recurrence(
                    rule = RepeatRule(RepeatFrequency.Daily, interval = 0, count = 0),
                    rdates = listOf(
                        RecurrenceDate(IcsTimeValue.Date(day.plusDays(2))),
                        RecurrenceDate(IcsTimeValue.Date(day.plusDays(2))),
                    ),
                    exdates = listOf(
                        IcsTimeValue.Date(day.plusDays(3)),
                        IcsTimeValue.Date(day.plusDays(3)),
                    ),
                ),
            ),
        )
        assertNotNull(normalized)
        val rule = normalized.repeat
        assertNotNull(rule)
        assertEquals(1, rule.interval)
        assertNull(rule.count)
        assertEquals(1, normalized.recurrence?.rdates?.size)
        assertEquals(1, normalized.recurrence?.exdates?.size)
    }

    @Test
    fun cancelledEventsAreStoredAsConfirmed() {
        val normalized = EventNormalizer.normalize(event(status = EventStatus.Cancelled))
        assertNotNull(normalized)
        assertEquals(EventStatus.Confirmed, normalized.status)
    }

    @Test
    fun repeatRulesCompareByNameAndUntilDate() {
        val daily = RepeatRule(RepeatFrequency.Daily)
        assertTrue(EventNormalizer.sameRepeatRule(daily, daily.copy(raw = "FREQ=DAILY")))
        assertFalse(
            EventNormalizer.sameRepeatRule(
                daily.copy(until = IcsTimeValue.Date(day)),
                daily.copy(until = IcsTimeValue.Date(day.plusDays(1))),
            ),
        )
        assertTrue(
            EventNormalizer.sameRepeatRule(
                daily.copy(until = IcsTimeValue.Date(day)),
                daily.copy(until = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(9, 0)))),
            ),
        )
        assertFalse(EventNormalizer.sameRepeatRule(daily, null))
    }
}

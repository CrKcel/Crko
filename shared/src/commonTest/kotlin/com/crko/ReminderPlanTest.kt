package com.crko

import com.crko.domain.Alarm
import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.plusDays
import com.crko.i18n.EnStrings
import com.crko.state.planReminders
import com.crko.state.parseTriggerInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Trigger planning for system notifications: relative triggers against start or end (`RELATED=END`),
 * negative (after) minutes, all-day midnight bases, recurring occurrences, `REPEAT` continuations,
 * absolute triggers and the platform cap.
 */
class ReminderPlanTest {
    private val strings = EnStrings
    // UTC+8 without DST keeps the expected instants easy to read.
    private val zone = TimeZone.of("Asia/Shanghai")
    // 2026-10-27T09:00:00Z == 17:00 in Shanghai.
    private val now = Instant.fromEpochMilliseconds(1_793_101_200_000L)

    private fun localAt(date: String, hour: Int, minute: Int): Instant =
        LocalDateTime.parse("${date}T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:00")
            .toInstant(zone)

    private fun plan(event: Event, limit: Int = 64) =
        planReminders(listOf(event), emptyList(), strings, now, zone, limit = limit)

    /** Old tests named the positive minutes *before* the related time; the trigger stores the sign. */
    private fun reminder(minutesBefore: Int, atEnd: Boolean = false) = AlarmTrigger.Relative(
        duration = (-minutesBefore).minutes,
        related = if (atEnd) AlarmRelated.End else AlarmRelated.Start,
    )

    @Test
    fun aSimpleReminderFiresBeforeTheStart() {
        val event = testEvent(
            id = "e1",
            title = "Meeting",
            date = "2026-10-28",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            alarms = listOf(Alarm(trigger = reminder(15))),
        )
        val planned = plan(event)
        assertEquals(1, planned.size)
        assertEquals(localAt("2026-10-28", 9, 45).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals("Meeting", planned[0].title)
        assertEquals("15 min before · 10:00", planned[0].body)
        assertTrue(planned[0].key.startsWith("e:e1:"))
    }

    @Test
    fun anEndRelatedReminderFiresBeforeTheEnd() {
        val event = testEvent(
            id = "e2",
            title = "Workshop",
            date = "2026-10-28",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            alarms = listOf(
                Alarm(trigger = reminder(10, atEnd = true)),
                Alarm(trigger = reminder(10)),
            ),
        )
        val planned = plan(event).sortedBy { it.triggerAtMillis }
        // The start-based trigger sits ten minutes before the start; the end-based one before the end.
        assertEquals(localAt("2026-10-28", 9, 50).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(localAt("2026-10-28", 10, 50).toEpochMilliseconds(), planned[1].triggerAtMillis)
    }

    @Test
    fun aNegativeTriggerFiresAfterTheStart() {
        val event = testEvent(
            id = "e3",
            title = "Late",
            date = "2026-10-28",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            alarms = listOf(Alarm(trigger = reminder(-5))),
        )
        val planned = plan(event)
        assertEquals(localAt("2026-10-28", 10, 5).toEpochMilliseconds(), planned.single().triggerAtMillis)
        assertEquals("5 min after start · 10:00", planned.single().body)
    }

    @Test
    fun anAllDayReminderFiresBeforeMidnight() {
        val event = testEvent(
            id = "e4",
            title = "Holiday",
            date = "2026-10-28",
            startMinutes = 0,
            endMinutes = 0,
            allDay = true,
            alarms = listOf(Alarm(trigger = reminder(30))),
        )
        val planned = plan(event)
        assertEquals(localAt("2026-10-27", 23, 30).toEpochMilliseconds(), planned.single().triggerAtMillis)
        assertEquals("30 min before · Oct 28", planned.single().body)
    }

    @Test
    fun aZeroDurationEndRelatedReminderFiresAtTheStart() {
        val event = testEvent(
            id = "e5",
            title = "Point",
            date = "2026-10-28",
            startMinutes = 10 * 60,
            endMinutes = 10 * 60,
            alarms = listOf(
                Alarm(trigger = reminder(10, atEnd = true)),
                Alarm(trigger = reminder(10)),
            ),
        )
        val planned = plan(event)
        // End == start for a zero-length event, so both triggers land on the same minute.
        assertEquals(2, planned.size)
        assertEquals(localAt("2026-10-28", 9, 50).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(localAt("2026-10-28", 9, 50).toEpochMilliseconds(), planned[1].triggerAtMillis)
    }

    @Test
    fun recurringOccurrencesAreScheduledUntilTheHorizon() {
        val event = testEvent(
            id = "e6",
            title = "Standup",
            date = "2026-10-20",
            startMinutes = 10 * 60,
            endMinutes = 10 * 60 + 30,
            alarms = listOf(Alarm(trigger = reminder(15))),
            repeat = RepeatRule(frequency = RepeatFrequency.Daily),
            excludedDates = listOf("2026-10-28"),
        )
        val planned = plan(event)
        // Occurrences from the day after now until the horizon end (2026-10-27 + 60 days), minus
        // the excluded one. Past triggers of earlier occurrences are dropped.
        assertEquals(59, planned.size)
        assertEquals(localAt("2026-10-29", 9, 45).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(localAt("2026-12-26", 9, 45).toEpochMilliseconds(), planned.last().triggerAtMillis)
    }

    @Test
    fun anEventBeyondTheHorizonIsNotScheduledYet() {
        val event = testEvent(
            id = "e7",
            title = "Far away",
            date = "2027-03-01",
            startMinutes = 600,
            endMinutes = 660,
            alarms = listOf(Alarm(trigger = reminder(15))),
        )
        assertTrue(plan(event).isEmpty())
    }

    @Test
    fun repeatWithDurationExpandsIntoContinuations() {
        val event = testEvent(
            id = "e8",
            title = "Nagging",
            date = "2026-10-28",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            alarms = listOf(Alarm(trigger = reminder(0), repeat = 2, duration = 5.minutes)),
        )
        val planned = plan(event)
        assertEquals(3, planned.size)
        assertEquals(localAt("2026-10-28", 10, 0).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(localAt("2026-10-28", 10, 5).toEpochMilliseconds(), planned[1].triggerAtMillis)
        assertEquals(localAt("2026-10-28", 10, 10).toEpochMilliseconds(), planned[2].triggerAtMillis)
    }

    @Test
    fun repeatWithoutDurationDoesNotStackZeroLengthContinuations() {
        val event = testEvent(
            id = "e9",
            title = "Once",
            date = "2026-10-28",
            startMinutes = 10 * 60,
            endMinutes = 11 * 60,
            alarms = listOf(Alarm(trigger = reminder(0), repeat = 3)),
        )
        assertEquals(1, plan(event).size)
    }

    @Test
    fun anAbsoluteTriggerFiresOnceAtItsOwnInstant() {
        val event = testEvent(
            id = "e10",
            title = "Absolute",
            date = "2026-10-01",
            startMinutes = 600,
            endMinutes = 660,
            alarms = listOf(Alarm(trigger = AlarmTrigger.Absolute(IcsTimeValue.Utc(Instant.parse("2026-10-28T01:00:00Z"))))),
        )
        val planned = plan(event)
        assertEquals(1, planned.size)
        assertEquals(Instant.parse("2026-10-28T01:00:00Z").toEpochMilliseconds(), planned.single().triggerAtMillis)
    }

    @Test
    fun aFloatingAbsoluteTriggerUsesTheDeviceZone() {
        val parsed = parseTriggerInstant("20261028T010000", zone)
        assertEquals(LocalDateTime(2026, 10, 28, 1, 0).toInstant(zone), parsed)
        assertEquals(
            Instant.parse("2026-10-28T01:00:00Z"),
            parseTriggerInstant("2026-10-28T01:00:00Z", zone),
        )
        assertNull(parseTriggerInstant("not-a-time", zone))
        assertNull(parseTriggerInstant("", zone))
    }

    @Test
    fun anOffsetAbsoluteTriggerResolvesInUtc() {
        // 09:00 at +08:00 is 01:00Z; the device zone must not be consulted.
        assertEquals(
            Instant.parse("2026-10-28T01:00:00Z"),
            parseTriggerInstant("20261028T090000+0800", zone),
        )
        assertEquals(
            Instant.parse("2026-10-28T06:00:00Z"),
            parseTriggerInstant("20261028T010000-0500", zone),
        )
        // An extended form with an offset parses through the ISO path.
        assertEquals(
            Instant.parse("2026-10-28T01:00:00Z"),
            parseTriggerInstant("2026-10-28T09:00:00+08:00", zone),
        )
    }

    @Test
    fun emailAlarmsAndPastTriggersAreDropped() {
        val past = testEvent(
            id = "e11",
            title = "Already gone",
            date = "2026-10-27",
            startMinutes = 8 * 60,
            endMinutes = 9 * 60,
            alarms = listOf(Alarm(trigger = reminder(15))),
        )
        val email = testEvent(
            id = "e12",
            title = "Mailed",
            date = "2026-10-28",
            startMinutes = 600,
            endMinutes = 660,
            alarms = listOf(Alarm(action = "EMAIL", trigger = reminder(15))),
        )
        assertTrue(plan(past).isEmpty())
        assertTrue(plan(email).isEmpty())
    }

    @Test
    fun theCapKeepsTheEarliestReminders() {
        val events = (1..10).map { index ->
            testEvent(
                id = "cap-$index",
                title = "E$index",
                date = "2026-10-28",
                startMinutes = 8 * 60 + index,
                endMinutes = 9 * 60 + index,
                alarms = listOf(Alarm(trigger = reminder(15))),
            )
        }
        val planned = planReminders(events, emptyList(), strings, now, zone, limit = 3)
        assertEquals(3, planned.size)
        assertEquals(localAt("2026-10-28", 7, 46).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(localAt("2026-10-28", 7, 48).toEpochMilliseconds(), planned[2].triggerAtMillis)
    }

    @Test
    fun todoAlarmsFollowStartOrDueTimes() {
        val startBased = Todo(
            id = "t1",
            title = "With start",
            start = IcsTimeValue.Utc(Instant.parse("2026-10-28T02:00:00Z")),
            alarms = listOf(Alarm(trigger = reminder(30))),
        )
        val endBased = Todo(
            id = "t2",
            title = "With due",
            due = IcsTimeValue.Utc(Instant.parse("2026-10-28T04:00:00Z")),
            alarms = listOf(Alarm(trigger = reminder(15, atEnd = true))),
        )
        val done = Todo(
            id = "t3",
            title = "Finished",
            status = TodoStatus.Completed,
            start = IcsTimeValue.Utc(Instant.parse("2026-10-28T02:00:00Z")),
            alarms = listOf(Alarm(trigger = reminder(30))),
        )
        val planned = planReminders(emptyList(), listOf(startBased, endBased, done), strings, now, zone)
            .sortedBy { it.triggerAtMillis }
        assertEquals(2, planned.size)
        assertEquals(Instant.parse("2026-10-28T01:30:00Z").toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(Instant.parse("2026-10-28T03:45:00Z").toEpochMilliseconds(), planned[1].triggerAtMillis)
        assertTrue(planned[0].key.startsWith("t:t1:"))
        assertTrue(planned[1].key.startsWith("t:t2:"))
    }

    @Test
    fun aTodoWithoutTimesCannotScheduleRelativeAlarms() {
        val todo = Todo(
            id = "t4",
            title = "Dateless",
            due = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
            alarms = listOf(Alarm(trigger = reminder(30))),
        )
        assertTrue(planReminders(emptyList(), listOf(todo), strings, now, zone).isEmpty())
    }

    @Test
    fun byHourAlarmsFireForEveryStartOfTheDay() {
        val event = testEvent(
            id = "rounds",
            title = "Rounds",
            date = "2026-10-28",
            startMinutes = 9 * 60,
            endMinutes = 10 * 60,
            repeat = RepeatRule(RepeatFrequency.Daily, byHour = listOf(9, 17)),
            alarms = listOf(Alarm(trigger = reminder(15))),
        )
        // The first day of the series opens with both rounds; the cap keeps later days out.
        val planned = plan(event).take(2)
        assertEquals(
            listOf(localAt("2026-10-28", 8, 45), localAt("2026-10-28", 16, 45)).map { it.toEpochMilliseconds() },
            planned.map { it.triggerAtMillis },
        )
    }

    @Test
    fun aRecurringTodoKeepsTheReminderTimesOfItsOwnZone() {
        // The todo lives in Shanghai, the device in New York. Occurrence dates come from the todo's own
        // wall clock, so resolving each one through the device zone put the series a whole day off
        // (09:00 in Shanghai is still the previous evening in New York).
        val newYork = TimeZone.of("America/New_York")
        val shanghai = TimeZone.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-26T00:00:00Z")
        val todo = Todo(
            id = "t6",
            title = "Standup",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Asia/Shanghai"),
            repeat = RepeatRule(RepeatFrequency.Daily, count = 3),
            alarms = listOf(Alarm(trigger = AlarmTrigger.Relative((-30).minutes))),
        )
        val planned = planReminders(emptyList(), listOf(todo), strings, now, newYork)
        assertEquals(
            listOf("2026-10-27", "2026-10-28", "2026-10-29").map { day ->
                LocalDateTime.parse("${day}T09:00:00").toInstant(shanghai) - 30.minutes
            }.map { it.toEpochMilliseconds() },
            planned.map { it.triggerAtMillis },
        )
    }

    @Test
    fun aTodoAlarmRepeatKeepsItsContinuations() {
        val todo = Todo(
            id = "t6r",
            title = "Call back",
            start = IcsTimeValue.Utc(now + 30.minutes),
            alarms = listOf(
                Alarm(
                    trigger = AlarmTrigger.Relative((-10).minutes),
                    repeat = 2,
                    duration = 30.seconds,
                ),
            ),
        )
        // `REPEAT` counts the firings after the first one, spaced by `DURATION`. The event path emits
        // them; the todo path stopped at the first trigger, so an imported repeating todo reminder went
        // quiet after a single notification.
        val planned = planReminders(emptyList(), listOf(todo), strings, now, zone)
        assertEquals(
            listOf(now + 20.minutes, now + 20.minutes + 30.seconds, now + 20.minutes + 60.seconds)
                .map { it.toEpochMilliseconds() },
            planned.map { it.triggerAtMillis },
        )
        // Every continuation is its own scheduled trigger, not the same minute bucket.
        assertEquals(3, planned.map { it.key }.distinct().size)
    }

    @Test
    fun aRecurringTodoPlansOneReminderPerOccurrence() {
        val todo = Todo(
            id = "t5",
            title = "Dose",
            start = IcsTimeValue.Utc(localAt("2026-10-27", 21, 0)),
            repeat = RepeatRule(RepeatFrequency.Daily, count = 2),
            alarms = listOf(Alarm(trigger = reminder(30))),
        )
        val planned = planReminders(emptyList(), listOf(todo), strings, now, zone)
        assertEquals(
            listOf(localAt("2026-10-27", 20, 30), localAt("2026-10-28", 20, 30)).map { it.toEpochMilliseconds() },
            planned.map { it.triggerAtMillis },
        )
        // Each occurrence carries its own key, so rescheduling replaces them separately.
        assertTrue(planned[0].key.contains(":2026-10-27:"))
        assertTrue(planned[1].key.contains(":2026-10-28:"))
    }

    @Test
    fun multiDayEndRelatedAlarmsFollowEachOccurrence() {
        val event = testEvent(
            id = "e13",
            title = "Retreat",
            date = "2026-10-01",
            startMinutes = 9 * 60,
            endMinutes = 17 * 60,
            endDate = "2026-10-04",
            alarms = listOf(Alarm(trigger = reminder(60, atEnd = true))),
            repeat = RepeatRule(frequency = RepeatFrequency.Monthly, byMonthDay = listOf(1)),
        )
        val planned = plan(event)
        // One occurrence per month. The October trigger (Oct 4, 16:00) already lies in the past,
        // so November and December remain within the horizon.
        assertEquals(2, planned.size)
        assertEquals(localAt("2026-11-04", 16, 0).toEpochMilliseconds(), planned[0].triggerAtMillis)
        assertEquals(localAt("2026-12-04", 16, 0).toEpochMilliseconds(), planned[1].triggerAtMillis)
    }
}

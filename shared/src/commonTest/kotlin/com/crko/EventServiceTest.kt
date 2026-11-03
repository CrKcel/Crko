package com.crko

import com.crko.domain.Alarm
import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.MaxEndMinutes
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RecurrenceRange
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.absoluteTrigger
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.movedByWallDays
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.relatedToEnd
import com.crko.domain.triggerMinutes
import com.crko.domain.wallDate
import com.crko.migration.legacyEvent
import com.crko.state.EditScope
import com.crko.state.WriteResult
import com.crko.state.event.endMinutesAfterStartChange
import com.crko.state.event.updateReminderAlarms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class EventServiceTest {

    @Test
    fun eventValidationRejectsBlankTitlesAndNegativeDurations() {
        val f = Fixture()
        val base = legacyEvent(null, "Meeting", "2026-10-27", 540, 600)
        assertEquals(WriteResult.EmptyTitle, f.holder.saveEvent(base.copy(title = "   ")))
        // A zero-length timed event is valid RFC 5545; only an end before the start is rejected.
        assertEquals(WriteResult.Success, f.holder.saveEvent(base.copy(end = null)))
        assertEquals(
            WriteResult.InvalidTimeRange,
            f.holder.saveEvent(base.copy(end = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 8, 20)))),
        )
        assertEquals("Meeting", f.holder.eventsOn(f.holder.today).single().title)
    }

    @Test
    fun savingAnExistingIdUpdatesInsteadOfDuplicating() {
        val f = Fixture()
        val event = legacyEvent("e1", "Draft", "2026-10-27", 60, 120)
        f.holder.saveEvent(event)
        f.holder.saveEvent(event.copy(title = "Final").withTimes(event.wallDay, 90, 150))
        val stored = f.holder.appState.events.single()
        assertEquals("Final", stored.title)
        assertEquals(90, stored.startMinutes)
        assertEquals(1, f.storage.state.events.size)
    }

    @Test
    fun newEventsReceiveAPersistedUniqueIdFromTheStateLayer() {
        val f = Fixture()
        assertEquals(
            WriteResult.Success,
            f.holder.saveEvent(legacyEvent(null, "First", "2026-10-27", 540, 600)),
        )
        assertEquals(
            WriteResult.Success,
            f.holder.saveEvent(legacyEvent(null, "Second", "2026-10-27", 660, 720)),
        )
        val ids = f.storage.state.events.map { it.id }
        assertEquals(listOf("id-0", "id-1"), ids)
        assertTrue(ids.all { it != null && it.isNotBlank() })
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun deletingAnUnsavedEventIsANoOp() {
        val f = Fixture()
        f.holder.saveEvent(legacyEvent("e1", "Keep", "2026-10-27", 60, 120))
        f.holder.deleteEvent(null)
        assertEquals(1, f.holder.appState.events.size)
        f.holder.deleteEvent("e1")
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun endTimeIsClampedBeforeMidnight() {
        val f = Fixture()
        f.holder.saveEvent(legacyEvent("e1", "Late", "2026-10-27", 1400, 2000))
        // 24:00 is not representable as LocalTime; the editor would crash recomposing it.
        assertEquals(MaxEndMinutes, f.holder.appState.events.single().endMinutes)
    }

    @Test
    fun dailyRepeatOccursOnEveryDayFromTheStartDate() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("e1", "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        assertEquals(1, f.holder.eventsOn(start).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(1)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(30)).size)
        assertTrue(f.holder.eventsOn(start.plusDays(-1)).isEmpty())
        // The stored series stays a single record.
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun weeklyRepeatOccursOnTheAnchorWeekday() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("e1", "Review", start.toString(), 600, 660, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(7)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(21)).size)
    }

    @Test
    fun customRepeatUsesTheSelectedWeekdays() {
        val f = Fixture()
        val start = f.holder.today
        // Tuesday, repeated on Monday and Thursday.
        f.holder.saveEvent(
            legacyEvent(
                "e1",
                "Gym",
                start.toString(),
                420,
                480,
                repeat = RepeatRule(
                    RepeatFrequency.Weekly,
                    byDay = listOf(OrdinalWeekday(0, 0), OrdinalWeekday(0, 3)),
                ),
            ),
        )
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(2)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(6)).size)
    }

    @Test
    fun weeklyRepeatWithoutSelectedDaysUsesTheStartWeekday() {
        val f = Fixture()
        val result = f.holder.saveEvent(
            legacyEvent("e1", "Gym", "2026-10-27", 420, 480, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        assertEquals(WriteResult.Success, result)
        assertEquals(1, f.holder.appState.events.size)
        assertTrue(f.holder.appState.events.single().occursOn(LocalDate(2026, 11, 3)))
    }

    @Test
    fun clearingADaySkipsOneOccurrenceAndKeepsTheSeries() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("e1", "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        f.holder.deleteEventsOn(start.plusDays(1))
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(2)).size)
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun clearingADayRemovesOneOffEvents() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(legacyEvent("e1", "Dentist", start.toString(), 540, 600))
        f.holder.deleteEventsOn(start)
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun savingOneOccurrenceCreatesAnOverrideAndSkipsTheOriginalSlot() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(1)
        val result = f.holder.saveEvent(
            series.copy(title = "Moved").withTimes(occurrence, 660, 720),
            occurrenceDate = occurrence,
            scope = EditScope.ThisEvent,
        )
        assertEquals(WriteResult.Success, result)
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        val updatedSeries = events.first { it.id == series.id }
        assertTrue(occurrence.toString() in updatedSeries.excludedDates)
        val override = f.holder.eventsOn(occurrence).single()
        assertEquals("Moved", override.title)
        assertEquals(660, override.startMinutes)
        assertEquals(series.uid, override.icsUid)
        assertEquals(series.recurrenceSlotOn(occurrence), override.recurrenceId)
        assertEquals("Standup", f.holder.eventsOn(occurrence.plusDays(1)).single().title)
    }

    @Test
    fun savingThisAndFutureSplitsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        val split = f.holder.today.plusDays(2)
        f.holder.saveEvent(
            series.copy(title = "New standup").withTimes(split, 600, 615),
            occurrenceDate = split,
            scope = EditScope.ThisAndFuture,
        )
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        val oldSeries = events.first { it.id == series.id }
        assertEquals(IcsTimeValue.Date(split.plusDays(-1)), oldSeries.repeat?.until)
        assertEquals("Standup", f.holder.eventsOn(split.plusDays(-1)).single().title)
        val newSeries = events.first { it.id != series.id }
        assertEquals(split.toString(), newSeries.date)
        assertEquals("New standup", f.holder.eventsOn(split).single().title)
        assertEquals("New standup", f.holder.eventsOn(split.plusDays(1)).single().title)
    }

    @Test
    fun savingThisAndFutureAtTheAnchorEditsTheWholeSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        f.holder.saveEvent(
            series.copy(title = "Renamed").withTimes(f.holder.today, 600, 615),
            occurrenceDate = f.holder.today,
            scope = EditScope.ThisAndFuture,
        )
        val stored = f.holder.appState.events.single()
        assertEquals(series.id, stored.id)
        assertEquals("Renamed", stored.title)
        assertTrue(stored.occursOn(f.holder.today.plusDays(10)))
    }

    @Test
    fun savingThisAndFuturePartitionsRDatesAcrossTheSplit() {
        val f = Fixture()
        val split = f.holder.today.plusDays(2)
        val before = split.plusDays(-1)
        val after = split.plusDays(10)
        f.holder.saveEvent(
            Event(
                "series",
                "Standup",
                IcsTimeValue.Floating(LocalDateTime(f.holder.today, LocalTime(9, 0))),
                IcsTimeValue.Floating(LocalDateTime(f.holder.today, LocalTime(9, 15))),
                recurrence = Recurrence(
                    rule = RepeatRule(RepeatFrequency.Daily),
                    rdates = listOf(
                        RecurrenceDate(IcsTimeValue.Date(before)),
                        RecurrenceDate(IcsTimeValue.Date(after)),
                    ),
                ),
            ),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "New standup").movedTo(split),
            occurrenceDate = split,
            scope = EditScope.ThisAndFuture,
        )
        val events = f.holder.appState.events
        val oldSeries = events.first { it.id == series.id }
        val newSeries = events.first { it.id != series.id }
        // Each extra date belongs to the segment that covers it; keeping both on one side would
        // duplicate the occurrence or bring it back as a phantom on export.
        assertEquals(
            listOf(IcsTimeValue.Date(before)),
            oldSeries.recurrence?.rdates?.map { it.value },
        )
        assertEquals(
            listOf(IcsTimeValue.Date(after)),
            newSeries.recurrence?.rdates?.map { it.value },
        )
        assertEquals("Standup", f.holder.eventsOn(split.plusDays(-1)).single().title)
        assertEquals("New standup", f.holder.eventsOn(split.plusDays(10)).single().title)
    }

    @Test
    fun deletingOneOccurrenceKeepsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        f.holder.deleteEvent(series, occurrenceDate = f.holder.today.plusDays(1), scope = EditScope.ThisEvent)
        assertEquals(1, f.holder.appState.events.size)
        assertTrue(f.holder.eventsOn(f.holder.today.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(f.holder.today.plusDays(2)).size)
    }

    @Test
    fun deletingThisAndFutureCutsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        val cut = f.holder.today.plusDays(2)
        f.holder.deleteEvent(series, occurrenceDate = cut, scope = EditScope.ThisAndFuture)
        assertEquals(1, f.holder.appState.events.size)
        val stored = f.holder.appState.events.single()
        assertEquals(IcsTimeValue.Date(cut.plusDays(-1)), stored.repeat?.until)
        assertEquals(1, f.holder.eventsOn(cut.plusDays(-1)).size)
        assertTrue(f.holder.eventsOn(cut).isEmpty())
    }

    @Test
    fun deletingTheWholeSeriesAlsoRemovesItsOverrides() {
        val f = Fixture()
        val series = f.dailySeries()
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(f.holder.today.plusDays(1)),
            occurrenceDate = f.holder.today.plusDays(1),
            scope = EditScope.ThisEvent,
        )
        assertEquals(2, f.holder.appState.events.size)
        f.holder.deleteEvent(series, scope = EditScope.AllEvents)
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun allDayEventKeepsAnInclusiveEndDate() {
        val f = Fixture()
        val start = f.holder.today
        val result = f.holder.saveEvent(
            legacyEvent(
                id = "trip",
                title = "Trip",
                date = start.toString(),
                startMinutes = 0,
                endMinutes = 0,
                endDate = start.plusDays(2).toString(),
                allDay = true,
            ),
        )
        assertEquals(WriteResult.Success, result)
        val stored = f.holder.appState.events.single()
        assertTrue(stored.allDay)
        assertEquals(0, stored.startMinutes)
        assertEquals(start.plusDays(2).toString(), stored.endDate)
        assertTrue(stored.occursOn(start))
        assertTrue(stored.occursOn(start.plusDays(2)))
        assertFalse(stored.occursOn(start.plusDays(3)))
    }

    @Test
    fun eventMayEndExactlyAtMidnight() {
        val f = Fixture()
        val start = f.holder.today
        val result = f.holder.saveEvent(
            legacyEvent("late", "Late", start.toString(), 23 * 60, 0, endDate = start.plusDays(1).toString()),
        )
        assertEquals(WriteResult.Success, result)
        val stored = f.holder.appState.events.single()
        assertEquals(0, stored.endMinutes)
        assertEquals(start.plusDays(1).toString(), stored.endDate)
        assertTrue(stored.occursOn(start))
        assertFalse(stored.occursOn(start.plusDays(1)))
    }

    @Test
    fun zonedEventKeepsItsRfcValueAndProjectsForDisplay() {
        val event = Event(
            id = "zoned",
            title = "Zoned",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 7, 27, 9, 0), "Europe/Berlin"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 7, 27, 10, 0), "Europe/Berlin"),
        )
        // The persisted value is the one the calendar file describes, not a device-zone projection.
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 7, 27, 9, 0), "Europe/Berlin"), event.start)
        // Display projection moves with the viewer's zone (Berlin summer time is UTC+2).
        assertEquals(LocalDateTime(2026, 7, 27, 7, 0), event.localStart(TimeZone.UTC))
        assertEquals(LocalDateTime(2026, 7, 27, 8, 0), event.localEnd(TimeZone.UTC))
    }

    @Test
    fun movingASeriesKeepsItsZoneAndWallClock() {
        val series = Event(
            id = "series",
            title = "Series",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 0, 30), "Asia/Shanghai"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 1, 0), "Asia/Shanghai"),
            recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Daily)),
        )
        val moved = series.movedByWallDays(2)
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 29, 0, 30), "Asia/Shanghai"), moved.start)
        assertEquals("Asia/Shanghai", moved.timeZoneId)
    }

    @Test
    fun editingARecurringOccurrenceForTheWholeSeriesKeepsTheAnchor() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(3)
        f.holder.saveEvent(
            series.copy(title = "Renamed").withTimes(occurrence, 600, 615),
            occurrenceDate = occurrence,
            scope = EditScope.AllEvents,
        )
        val stored = f.holder.appState.events.single()
        assertEquals(f.holder.today.toString(), stored.date)
        assertEquals("Renamed", stored.title)
        assertEquals(600, stored.startMinutes)
        assertTrue(stored.occursOn(f.holder.today.plusDays(10)))
    }

    @Test
    fun clearingRepeatOnTheWholeSeriesRemovesExceptions() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(1)
        f.holder.saveEvent(series.copy(title = "Moved").movedTo(occurrence), occurrence, EditScope.ThisEvent)
        assertEquals(2, f.holder.appState.events.size)
        f.holder.saveEvent(series.copy(recurrence = null).movedTo(occurrence), occurrence, EditScope.AllEvents)
        val stored = f.holder.appState.events.single()
        assertNull(stored.repeat)
        assertTrue(stored.excludedDates.isEmpty())
        assertTrue(f.holder.eventsOn(f.holder.today).isNotEmpty())
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun thisAndFutureKeepsTheRuleButDropsItsOldBound() {
        val f = Fixture()
        val series = f.dailySeries(count = 5)
        val split = f.holder.today.plusDays(2)
        f.holder.saveEvent(series.copy(title = "New").movedTo(split), split, EditScope.ThisAndFuture)
        val newSeries = f.holder.appState.events.first { it.id != series.id }
        assertNull(newSeries.repeat?.count)
        assertNull(newSeries.repeat?.until)
        assertEquals(split.toString(), newSeries.date)
        assertTrue(newSeries.occursOn(split.plusDays(30)))
    }

    @Test
    fun movingOneOccurrenceMovesItsStartDate() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(legacyEvent("week", "Weekly", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Weekly)))
        val series = f.holder.appState.events.single()
        val occurrence = start.plusDays(7)
        val moved = start.plusDays(2)
        f.holder.saveEvent(series.copy(title = "Moved").movedTo(moved), occurrence, EditScope.ThisEvent)
        val override = f.holder.eventsOn(moved).single()
        assertEquals("Moved", override.title)
        assertEquals(series.recurrenceSlotOn(occurrence), override.recurrenceId)
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun movingOneOccurrenceToTheSeriesAnchorIsApplied() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("week", "Weekly", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        val series = f.holder.appState.events.single()
        val occurrence = start.plusDays(7)
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(start),
            occurrence,
            EditScope.ThisEvent,
        )

        val override = f.holder.appState.events.first { it.title == "Moved" }
        assertEquals(start.toString(), override.date)
        assertEquals(series.recurrenceSlotOn(occurrence), override.recurrenceId)
        assertTrue(f.holder.eventsOn(start).any { it.title == "Moved" })
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun changingTheSeriesDateShiftsExceptionsAndExclusions() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("daily", "Daily", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(start.plusDays(2)),
            start.plusDays(1),
            EditScope.ThisEvent,
        )

        f.holder.saveEvent(
            series.copy(title = "Shifted").movedTo(start.plusDays(1)),
            start,
            EditScope.AllEvents,
        )

        val shiftedSeries = f.holder.appState.events.first { it.title == "Shifted" }
        val shiftedException = f.holder.appState.events.first { it.title == "Moved" }
        assertEquals(start.plusDays(1).toString(), shiftedSeries.date)
        assertEquals(listOf(start.plusDays(2).toString()), shiftedSeries.excludedDates)
        assertEquals(start.plusDays(3).toString(), shiftedException.date)
        assertEquals(start.plusDays(2), shiftedException.recurrenceId?.wallDate)
    }

    @Test
    fun movingTheWholeSeriesFromALaterOccurrenceUsesTheDateDelta() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("daily", "Daily", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val series = f.holder.appState.events.single()

        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(start),
            start.plusDays(3),
            EditScope.AllEvents,
        )

        val stored = f.holder.appState.events.single()
        assertEquals(start.plusDays(-3).toString(), stored.date)
        assertEquals(1, f.holder.eventsOn(start).size)
    }

    @Test
    fun changingTheSeriesDateShiftsAnUnchangedUntil() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent(
                "bounded",
                "Bounded",
                start.toString(),
                540,
                555,
                repeat = RepeatRule(RepeatFrequency.Daily, until = IcsTimeValue.Date(start.plusDays(5))),
            ),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "Shifted").movedTo(start.plusDays(1)),
            start,
            EditScope.AllEvents,
        )
        val stored = f.holder.appState.events.single()
        assertEquals(start.plusDays(1).toString(), stored.date)
        assertEquals(IcsTimeValue.Date(start.plusDays(6)), stored.repeat?.until)
    }

    @Test
    fun changingTheSeriesDateShiftsImportedFutureSegments() {
        val f = Fixture()
        val today = f.holder.today
        f.holder.importEvents(
            listOf(
                Event(
                    id = null,
                    title = "Base",
                    start = IcsTimeValue.Floating(LocalDateTime(today, LocalTime(9, 0))),
                    end = IcsTimeValue.Floating(LocalDateTime(today, LocalTime(10, 0))),
                    recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Daily)),
                    icsUid = "base-uid",
                ),
                Event(
                    id = null,
                    title = "Future",
                    start = IcsTimeValue.Floating(LocalDateTime(today.plusDays(2), LocalTime(9, 0))),
                    end = IcsTimeValue.Floating(LocalDateTime(today.plusDays(2), LocalTime(10, 0))),
                    recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Daily, count = 5)),
                    icsUid = "base-uid",
                    recurrenceId = IcsTimeValue.Floating(LocalDateTime(today.plusDays(2), LocalTime(9, 0))),
                    recurrenceRange = RecurrenceRange.ThisAndFuture,
                ),
            ),
        )
        val base = f.holder.appState.events.first { it.icsUid == "base-uid" }
        val futureUid = f.holder.appState.events.first { it.title == "Future" }.uid

        f.holder.saveEvent(
            base.copy(title = "Shifted").movedTo(today.plusDays(1)),
            today,
            EditScope.AllEvents,
        )

        val shifted = f.holder.appState.events.first { it.title == "Future" }
        assertEquals(today.plusDays(3).toString(), shifted.date)
        assertEquals(today.plusDays(3), shifted.recurrenceId?.wallDate)
        assertEquals(futureUid, shifted.uid)
        assertEquals("base-uid", shifted.icsUid)
    }

    @Test
    fun editingMetadataKeepsTheImportedSourceButChangingTimeDropsIt() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.importEvents(
            listOf(
                legacyEvent(
                    id = null,
                    title = "Zoned",
                    date = start.toString(),
                    startMinutes = 9 * 60,
                    endMinutes = 10 * 60,
                    sourceStart = "20261027T090000",
                    sourceEnd = "20261027T100000",
                    timeZoneId = "Europe/Berlin",
                ),
            ),
        )
        val stored = f.holder.appState.events.single()
        f.holder.saveEvent(stored.copy(title = "Renamed"))
        val renamed = f.holder.appState.events.single()
        assertEquals("Europe/Berlin", renamed.timeZoneId)
        assertEquals("20261027T090000", renamed.sourceStart)

        f.holder.saveEvent(
            renamed.copy(
                start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 11, 0), "Europe/Berlin"),
                end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 12, 0), "Europe/Berlin"),
            ),
        )
        val retimed = f.holder.appState.events.single()
        // Editing the wall clock must keep the imported TZID so the next export stays zoned.
        assertEquals("Europe/Berlin", retimed.timeZoneId)
        assertEquals("20261027T110000", retimed.sourceStart)
    }

    @Test
    fun editingTheMiddleDayOfARepeatingMultiDayEventKeepsOneOccurrence() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent(
                "trip",
                "Trip",
                start.toString(),
                0,
                0,
                endDate = start.plusDays(2).toString(),
                allDay = true,
                repeat = RepeatRule(RepeatFrequency.Weekly),
            ),
        )
        val series = f.holder.appState.events.single()
        val middle = start.plusDays(1)
        val occurrence = series.occurrenceStartOn(middle)!!
        assertEquals(start, occurrence)
        f.holder.saveEvent(
            series.copy(title = "Edited").withTimes(
                start,
                series.startMinutes,
                series.endMinutes,
                endDate = start.plusDays(2),
            ),
            occurrence,
            EditScope.ThisEvent,
        )
        assertEquals(1, f.holder.eventsOn(middle).size)
        assertEquals("Edited", f.holder.eventsOn(middle).single().title)
        assertEquals(series.recurrenceSlotOn(occurrence), f.holder.eventsOn(middle).single().recurrenceId)
    }

    @Test
    fun movingAZeroDurationStartKeepsTheZeroLength() {
        // The end of a zero-length event follows the new start instead of stretching to an hour.
        assertEquals(
            660,
            endMinutesAfterStartChange(zeroDuration = true, sameDay = true, pickedMinutes = 660, endMinutes = 600),
        )
        // A start pushed past the end on the same day drags the end one hour ahead.
        assertEquals(
            750,
            endMinutesAfterStartChange(zeroDuration = false, sameDay = true, pickedMinutes = 690, endMinutes = 660),
        )
        assertEquals(
            MaxEndMinutes,
            endMinutesAfterStartChange(zeroDuration = false, sameDay = true, pickedMinutes = 1439, endMinutes = 600),
        )
        // A start inside the existing range, or a multi-day event, keeps the end.
        assertNull(
            endMinutesAfterStartChange(zeroDuration = false, sameDay = true, pickedMinutes = 600, endMinutes = 660),
        )
        assertNull(
            endMinutesAfterStartChange(zeroDuration = false, sameDay = false, pickedMinutes = 700, endMinutes = 660),
        )
    }

    @Test
    fun changingTheReminderKeepsOtherImportedAlarms() {
        val alarms = listOf(
            Alarm(trigger = AlarmTrigger.Relative((-15).minutes)),
            Alarm(action = "EMAIL", trigger = AlarmTrigger.Relative((-30).minutes), description = "mail"),
            Alarm(
                trigger = AlarmTrigger.Absolute(
                    IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
                ),
            ),
        )
        val updated = updateReminderAlarms(alarms, original = 15, reminder = 60)
        assertEquals(3, updated.size)
        assertEquals(60, updated[0].triggerMinutes)
        assertEquals("EMAIL", updated[1].action)
        assertEquals(
            IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
            updated[2].absoluteTrigger,
        )

        val removed = updateReminderAlarms(alarms, original = 15, reminder = null)
        assertEquals(2, removed.size)
        assertTrue(removed.none { it.absoluteTrigger == null && it.triggerMinutes == 15 })
    }

    @Test
    fun editingTheReminderKeepsTheEndRelatedBase() {
        val endBased = listOf(
            Alarm(trigger = AlarmTrigger.Relative((-30).minutes, AlarmRelated.End)),
        )
        // Changing only the minutes leaves the `RELATED=END` base alone.
        val kept = updateReminderAlarms(endBased, original = 30, reminder = 45, originalAtEnd = true, atEnd = true)
        assertTrue(kept.single().relatedToEnd)
        assertEquals(45, kept.single().triggerMinutes)
        // Switching the base is an explicit rewrite.
        val rebased = updateReminderAlarms(endBased, original = 30, reminder = 30, originalAtEnd = true, atEnd = false)
        assertFalse(rebased.single().relatedToEnd)
        // Same minutes and same base: the list is returned untouched.
        assertSame(endBased, updateReminderAlarms(endBased, original = 30, reminder = 30, originalAtEnd = true, atEnd = true))
        // A newly added reminder adopts the selected base.
        val added = updateReminderAlarms(emptyList(), original = null, reminder = 15, originalAtEnd = false, atEnd = true)
        assertTrue(added.single().relatedToEnd)
    }

    private fun Fixture.dailySeries(id: String = "series", count: Int? = null): Event {
        val start = holder.today
        holder.saveEvent(
            legacyEvent(id, "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily, count = count)),
        )
        return holder.appState.events.first { it.id == id }
    }
}

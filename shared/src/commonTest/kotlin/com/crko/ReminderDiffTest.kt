@file:OptIn(ExperimentalCoroutinesApi::class)

package com.crko

import com.crko.domain.Alarm
import com.crko.domain.AlarmTrigger
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.plusDays
import com.crko.i18n.AppLanguage
import com.crko.i18n.EnStrings
import com.crko.migration.legacyEvent
import com.crko.state.AppStateHolder
import com.crko.state.DarkModeSetting
import com.crko.state.EditScope
import com.crko.state.ReminderFingerprint
import com.crko.state.ScheduledReminder
import com.crko.state.diffReminders
import com.crko.state.planReminders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * F2.4: reminder scheduling is differential. A pass computes fingerprints per reminder, reuses the
 * fragments of records that did not change, and hands the platform only the delta — so editing one
 * record touches only its keys, journals and theme or language switches touch nothing, and process
 * start keeps the full replacement as the fallback path.
 */
class ReminderDiffTest {

    private fun reminder(key: String, at: Long, title: String) = ScheduledReminder(
        key = key,
        triggerAtMillis = at,
        title = title,
        body = "",
        fingerprint = ReminderFingerprint(triggerAtMillis = at, title = title, body = ""),
    )

    @Test
    fun aDiffRegistersNewAndChangedKeysWithoutCancellingThem() {
        val previous = listOf(reminder("k1", 100, "a"), reminder("k2", 200, "b"))
        val current = listOf(
            reminder("k1", 100, "a"),
            reminder("k2", 200, "b-changed"),
            reminder("k3", 300, "c"),
        )
        val diff = diffReminders(previous.associate { it.key to it.fingerprint }, current)
        assertTrue(diff.cancelKeys.isEmpty())
        assertEquals(listOf("k2", "k3"), diff.register.map { it.key })
    }

    @Test
    fun aDiffCancelsKeysThatLeftThePlan() {
        val previous = listOf(reminder("k1", 100, "a"), reminder("k2", 200, "b"))
        val diff = diffReminders(
            previous.associate { it.key to it.fingerprint },
            listOf(reminder("k1", 100, "a")),
        )
        assertEquals(setOf("k2"), diff.cancelKeys)
        assertTrue(diff.register.isEmpty())
    }

    @Test
    fun anUnchangedPlanProducesAnEmptyDiff() {
        val plan = listOf(reminder("k1", 100, "a"), reminder("k2", 200, "b"))
        val diff = diffReminders(plan.associate { it.key to it.fingerprint }, plan)
        assertTrue(diff.isEmpty)
    }

    @Test
    fun anUnknownPreviousPlanRegistersEverything() {
        val plan = listOf(reminder("k1", 100, "a"), reminder("k2", 200, "b"))
        val diff = diffReminders(emptyMap(), plan)
        assertEquals(setOf("k1", "k2"), diff.register.map { it.key }.toSet())
        assertTrue(diff.cancelKeys.isEmpty())
    }

    @Test
    fun editingOneEventTouchesOnlyItsKeys() = runTest {
        val scheduler = RecordingReminderScheduler()
        val holder = newHolder(scheduler = scheduler)
        val day = holder.today.plusDays(2).toString()
        holder.saveEvent(timedEvent("e1", "First", day, 600))
        holder.saveEvent(timedEvent("e2", "Second", day, 700))
        advanceUntilIdle()
        val before = scheduler.planKeys()
        assertEquals(2, before.size)

        holder.saveEvent(timedEvent("e1", "First edited", day, 600))
        advanceUntilIdle()

        val pass = assertIs<RecordingReminderScheduler.Pass.Execute>(scheduler.passes.last())
        assertEquals(1, pass.registered.size)
        assertTrue(pass.registered.single().startsWith("e:e1:"))
        assertTrue(pass.cancelled.isEmpty())
        // Same key, new content: the platform replaces it in place, nothing else is rebuilt.
        assertEquals(before, scheduler.planKeys())
    }

    @Test
    fun aJournalEditAndThemeSwitchDoNotTouchTheScheduler() = runTest {
        val scheduler = RecordingReminderScheduler()
        val holder = newHolder(scheduler = scheduler)
        holder.saveEvent(timedEvent("e1", "Meeting", holder.today.plusDays(2).toString(), 600))
        advanceUntilIdle()
        val before = scheduler.passes.size

        holder.addJournal("Note", "", null)
        holder.setDarkMode(DarkModeSetting.Dark)
        advanceUntilIdle()

        assertEquals(before, scheduler.passes.size)
    }

    @Test
    fun aLanguageSwitchDoesNotTouchTheScheduler() = runTest {
        val scheduler = RecordingReminderScheduler()
        val holder = newHolder(scheduler = scheduler)
        holder.saveEvent(timedEvent("e1", "Meeting", holder.today.plusDays(2).toString(), 600))
        advanceUntilIdle()
        val before = scheduler.passes.size

        // The derived body is localized at schedule time, so the display strings change — but the
        // fingerprint is language-independent and the platform is not asked to rebuild anything.
        holder.setLanguage(AppLanguage.Chinese)
        advanceUntilIdle()

        assertEquals(before, scheduler.passes.size)
    }

    @Test
    fun aRestartReplacesTheWholeSchedule() = runTest {
        val storage = MemoryStorage()
        val first = newHolder(storage = storage)
        first.saveEvent(timedEvent("e1", "Meeting", first.today.plusDays(2).toString(), 600))
        advanceUntilIdle()

        // A fresh holder stands in for a new process; its first pass is the fallback replacement.
        val scheduler = RecordingReminderScheduler()
        val second = newHolder(storage = storage, scheduler = scheduler)
        advanceUntilIdle()

        val pass = assertIs<RecordingReminderScheduler.Pass.ReplaceAll>(scheduler.passes.single())
        assertTrue(pass.registered.single().startsWith("e:e1:"))
    }

    @Test
    fun aDayRolloverRegistersTheOccurrenceThatEnteredTheHorizon() = runTest {
        val clock = MutableClock(FakeClockMillis)
        val scheduler = RecordingReminderScheduler()
        val holder = newHolder(clock = clock, scheduler = scheduler)
        // A long daily series starting well after the fixture instant: every trigger stays in the
        // future across the rollover, and the horizon picks up exactly one new occurrence per day.
        holder.saveEvent(
            legacyEvent(
                "e1",
                "Daily",
                holder.today.plusDays(10).toString(),
                600,
                660,
                alarms = listOf(Alarm(trigger = AlarmTrigger.Relative((-15).minutes))),
                repeat = RepeatRule(frequency = RepeatFrequency.Daily, count = 100),
            ),
        )
        advanceUntilIdle()
        val before = scheduler.planKeys()

        clock.millis += 24 * 60 * 60 * 1000L
        holder.refreshToday()
        advanceUntilIdle()

        val pass = assertIs<RecordingReminderScheduler.Pass.Execute>(scheduler.passes.last())
        assertEquals(1, pass.registered.size)
        assertTrue(pass.registered.single() !in before)
        assertTrue(pass.cancelled.isEmpty())
    }

    @Test
    fun aRecurringExceptionMovesOnlyThatOccurrence() = runTest {
        val scheduler = RecordingReminderScheduler()
        val holder = newHolder(scheduler = scheduler)
        val series = legacyEvent(
            "e1",
            "Standup",
            holder.today.plusDays(2).toString(),
            600,
            660,
            alarms = listOf(Alarm(trigger = AlarmTrigger.Relative((-15).minutes))),
            repeat = RepeatRule(frequency = RepeatFrequency.Daily, count = 100),
        )
        holder.saveEvent(series)
        advanceUntilIdle()
        val before = scheduler.planKeys()

        // Editing the occurrence three days out in place: it becomes an exception of the series.
        val occurrence = holder.today.plusDays(5)
        holder.saveEvent(
            series.movedTo(occurrence).copy(title = "Standup moved"),
            occurrenceDate = occurrence,
            scope = EditScope.ThisEvent,
        )
        advanceUntilIdle()

        val pass = assertIs<RecordingReminderScheduler.Pass.Execute>(scheduler.passes.last())
        // The skipped slot's key is cancelled, the exception's own key replaces it, and the other
        // occurrences stay exactly where they were.
        assertEquals(1, pass.cancelled.size)
        assertTrue(pass.cancelled.single().startsWith("e:e1:"))
        assertEquals(1, pass.registered.size)
        assertFalse(pass.registered.single().startsWith("e:e1:"))
        assertEquals(before - pass.cancelled + pass.registered.toSet(), scheduler.planKeys())
    }

    @Test
    fun theCapKeepsPruningStableUnderEdits() = runTest {
        val scheduler = RecordingReminderScheduler()
        val holder = newHolder(scheduler = scheduler)
        val day = holder.today.plusDays(2).toString()
        // Seventy events a minute apart: the plan keeps the earliest sixty-four.
        repeat(70) { index ->
            holder.saveEvent(timedEvent("e$index", "E$index", day, 600 + index))
        }
        advanceUntilIdle()
        val planned = scheduler.planKeys()
        assertEquals(64, planned.size)
        assertFalse(planned.any { it.startsWith("e:e69:") })
        val before = scheduler.passes.size

        // Editing the event outside the cap changes its fingerprint, but the capped plan is
        // identical — the platform is not contacted at all.
        holder.saveEvent(timedEvent("e69", "E69 edited", day, 669))
        advanceUntilIdle()
        assertEquals(before, scheduler.passes.size)

        // Editing one inside the cap moves exactly its key.
        holder.saveEvent(timedEvent("e5", "E5 edited", day, 605))
        advanceUntilIdle()
        val pass = assertIs<RecordingReminderScheduler.Pass.Execute>(scheduler.passes.last())
        assertEquals(1, pass.registered.size)
        assertTrue(pass.registered.single().startsWith("e:e5:"))
    }

    @Test
    fun aDeniedPermissionKeepsTheNextDiffHonest() = runTest {
        val scheduler = RecordingReminderScheduler()
        scheduler.granted = false
        val holder = newHolder(scheduler = scheduler)
        val day = holder.today.plusDays(2).toString()
        holder.saveEvent(timedEvent("e1", "Meeting", day, 600))
        advanceUntilIdle()

        // Permission arrives later; the next pass re-pushes the whole plan because the earlier
        // ones registered nothing.
        scheduler.granted = true
        holder.saveEvent(timedEvent("e1", "Meeting edited", day, 600))
        advanceUntilIdle()

        val pass = assertIs<RecordingReminderScheduler.Pass.Execute>(scheduler.passes.last())
        assertEquals(1, pass.registered.size)
        assertTrue(pass.registered.single().startsWith("e:e1:"))
    }

    @Test
    fun aZoneChangeReadsAsChangedTriggers() {
        val event = legacyEvent(
            "e1",
            "Meeting",
            "2026-10-29",
            600,
            660,
            alarms = listOf(Alarm(trigger = AlarmTrigger.Relative((-15).minutes))),
        )
        val now = Instant.fromEpochMilliseconds(FakeClockMillis)
        val shanghai = planReminders(listOf(event), emptyList(), EnStrings, now, TimeZone.of("Asia/Shanghai"))
        val tokyo = planReminders(listOf(event), emptyList(), EnStrings, now, TimeZone.of("Asia/Tokyo"))
        // A floating wall time resolves differently in each zone, so every trigger instant moves
        // and the diff hands the platform the moved keys instead of leaving stale ones behind.
        assertTrue(shanghai.map { it.key }.toSet().intersect(tokyo.map { it.key }.toSet()).isEmpty())
        val diff = diffReminders(shanghai.associate { it.key to it.fingerprint }, tokyo)
        assertEquals(tokyo.map { it.key }, diff.register.map { it.key })
        assertEquals(shanghai.map { it.key }.toSet(), diff.cancelKeys)
    }

    // ----- fixtures -----

    private var nextId = 0

    private fun TestScope.newHolder(
        storage: MemoryStorage = MemoryStorage(),
        clock: Clock = FakeClock(FakeClockMillis),
        scheduler: RecordingReminderScheduler = RecordingReminderScheduler(),
    ): AppStateHolder = AppStateHolder(
        scope = this,
        initialSystemLocale = "en-US",
        clock = clock,
        idProvider = { "id-${nextId++}" },
        storage = storage,
        persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
        reminders = scheduler,
    )

    /** One-off fixture at [date] with a single reminder fifteen minutes before the start. */
    private fun timedEvent(id: String, title: String, date: String, startMinutes: Int) =
        legacyEvent(
            id,
            title,
            date,
            startMinutes,
            startMinutes + 60,
            alarms = listOf(Alarm(trigger = AlarmTrigger.Relative((-15).minutes))),
        )

    private fun RecordingReminderScheduler.planKeys(): Set<String> {
        var keys = emptySet<String>()
        passes.forEach { pass ->
            keys = when (pass) {
                is RecordingReminderScheduler.Pass.ReplaceAll -> pass.registered.toSet()
                is RecordingReminderScheduler.Pass.Execute -> keys - pass.cancelled + pass.registered.toSet()
            }
        }
        return keys
    }

    private class MutableClock(initial: Long) : Clock {
        var millis: Long = initial

        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    private companion object {
        // 2026-10-27T09:00:00Z, the state-holder fixture instant.
        const val FakeClockMillis = 1_793_101_200_000L
    }
}

package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.wallDate
import com.crko.state.event.RepeatEndMode
import com.crko.state.event.buildRule
import com.crko.state.event.minutesToTime
import com.crko.state.event.withRule
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Pure draft rules extracted from the event editor (D1). */
class EventDraftRulesTest {

    @Test
    fun minutesToTimeClampsToTheDay() {
        assertEquals(0, minutesToTime(-30).hour * 60 + minutesToTime(-30).minute)
        assertEquals(23 * 60 + 59, minutesToTime(24 * 60).hour * 60 + minutesToTime(24 * 60).minute)
        val nineThirty = minutesToTime(9 * 60 + 30)
        assertEquals(9, nineThirty.hour)
        assertEquals(30, nineThirty.minute)
    }

    @Test
    fun noFrequencyRemovesTheRule() {
        assertNull(
            buildRule(
                frequency = null,
                old = RepeatRule(frequency = RepeatFrequency.Daily),
                weekdays = emptySet(),
                interval = 1,
                endMode = RepeatEndMode.Never,
                count = null,
                until = LocalDate(2026, 10, 30),
            ),
        )
    }

    @Test
    fun weeklyWeekdaysBecomePlainByDayValues() {
        val rule = buildRule(
            frequency = RepeatFrequency.Weekly,
            old = null,
            weekdays = setOf(4, 0, 2),
            interval = 2,
            endMode = RepeatEndMode.Never,
            count = null,
            until = LocalDate(2026, 10, 30),
        )
        assertNotNull(rule)
        assertEquals(
            listOf(OrdinalWeekday(0, 0), OrdinalWeekday(0, 2), OrdinalWeekday(0, 4)),
            rule.byDay,
        )
        assertEquals(2, rule.interval)
        assertNull(rule.count)
        assertNull(rule.until)
    }

    @Test
    fun nonWeeklyFrequenciesKeepTheImportedByDay() {
        val old = RepeatRule(
            frequency = RepeatFrequency.Monthly,
            byDay = listOf(OrdinalWeekday(2, 0)),
            byMonthDay = listOf(15),
        )
        val rule = buildRule(
            frequency = RepeatFrequency.Monthly,
            old = old,
            weekdays = setOf(3),
            interval = 1,
            endMode = RepeatEndMode.Never,
            count = null,
            until = LocalDate(2026, 10, 30),
        )
        assertEquals(old.byDay, rule?.byDay)
        assertEquals(old.byMonthDay, rule?.byMonthDay)
    }

    @Test
    fun countModeKeepsTheCountAndDropsTheUntil() {
        val rule = buildRule(
            frequency = RepeatFrequency.Daily,
            old = RepeatRule(
                frequency = RepeatFrequency.Daily,
                until = IcsTimeValue.Date(LocalDate(2026, 11, 1)),
            ),
            weekdays = emptySet(),
            interval = 1,
            endMode = RepeatEndMode.Count,
            count = 5,
            until = LocalDate(2026, 11, 1),
        )
        assertEquals(5, rule?.count)
        assertNull(rule?.until)
    }

    @Test
    fun untilModeReusesTheImportedValueWhenTheDateIsUnchanged() {
        val imported = IcsTimeValue.Utc(Instant.parse("2026-10-30T09:00:00Z"))
        val old = RepeatRule(frequency = RepeatFrequency.Daily, until = imported)
        val rule = buildRule(
            frequency = RepeatFrequency.Daily,
            old = old,
            weekdays = emptySet(),
            interval = 1,
            endMode = RepeatEndMode.Until,
            count = null,
            until = imported.wallDate,
        )
        assertSame(imported, rule?.until)
    }

    @Test
    fun untilModeWritesAPlainDateWhenTheDateChanges() {
        val imported = IcsTimeValue.Utc(Instant.parse("2026-10-30T09:00:00Z"))
        val picked = LocalDate(2026, 11, 4)
        val old = RepeatRule(frequency = RepeatFrequency.Daily, until = imported)
        val rule = buildRule(
            frequency = RepeatFrequency.Daily,
            old = old,
            weekdays = emptySet(),
            interval = 1,
            endMode = RepeatEndMode.Until,
            count = null,
            until = picked,
        )
        assertEquals(IcsTimeValue.Date(picked), rule?.until)
    }

    @Test
    fun anUntouchedRuleKeepsItsRawText() {
        val old = RepeatRule(
            frequency = RepeatFrequency.Daily,
            interval = 1,
            raw = "FREQ=DAILY;X-UNKNOWN=1",
        )
        val rule = buildRule(
            frequency = RepeatFrequency.Daily,
            old = old,
            weekdays = emptySet(),
            interval = 1,
            endMode = RepeatEndMode.Never,
            count = null,
            until = LocalDate(2026, 10, 30),
        )
        assertEquals("FREQ=DAILY;X-UNKNOWN=1", rule?.raw)
    }

    @Test
    fun anEditedRuleDropsTheRawText() {
        val old = RepeatRule(
            frequency = RepeatFrequency.Daily,
            interval = 1,
            raw = "FREQ=DAILY;X-UNKNOWN=1",
        )
        val rule = buildRule(
            frequency = RepeatFrequency.Daily,
            old = old,
            weekdays = emptySet(),
            interval = 2,
            endMode = RepeatEndMode.Never,
            count = null,
            until = LocalDate(2026, 10, 30),
        )
        assertEquals(2, rule?.interval)
        assertNull(rule?.raw)
        // A zero interval is normalised instead of producing an invalid rule.
        val zero = buildRule(
            frequency = RepeatFrequency.Daily,
            old = null,
            weekdays = emptySet(),
            interval = 0,
            endMode = RepeatEndMode.Never,
            count = null,
            until = LocalDate(2026, 10, 30),
        )
        assertEquals(1, zero?.interval)
    }

    @Test
    fun withRuleDropsTheRecurrenceWhenTheRuleIsRemoved() {
        assertNull((null as Recurrence?).withRule(null))
        val rule = RepeatRule(frequency = RepeatFrequency.Weekly)
        assertEquals(Recurrence(rule = rule), (null as Recurrence?).withRule(rule))
        val existing = Recurrence(rule = RepeatRule(frequency = RepeatFrequency.Daily), rawRule = "x")
        assertEquals(Recurrence(rule = rule, rawRule = "x"), existing.withRule(rule))
        assertNull(existing.withRule(null))
    }
}

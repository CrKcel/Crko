package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
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
}

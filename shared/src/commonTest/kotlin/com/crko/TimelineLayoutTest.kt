package com.crko

import com.crko.migration.legacyEvent
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.ui.schedule.layoutEvents
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private val layoutDay = LocalDate(2026, 10, 27)

private fun event(id: String, start: Int, end: Int) = legacyEvent(id, id, "2026-10-27", start, end)

class TimelineLayoutTest {

    @Test
    fun expandedStartsPlaceSeparateBlocks() {
        // A BYHOUR rule turns one event into two blocks on the same day.
        val rounds = legacyEvent(
            id = "rounds",
            title = "Rounds",
            date = "2026-10-27",
            startMinutes = 9 * 60,
            endMinutes = 10 * 60,
            repeat = RepeatRule(RepeatFrequency.Daily, byHour = listOf(9, 17)),
        )
        val placed = layoutEvents(listOf(rounds), LocalDate(2026, 10, 28))
        assertEquals(listOf(540, 1020), placed.map { it.startMinutes })
        assertEquals(listOf(600, 1080), placed.map { it.endMinutes })
        assertEquals(listOf(0, 0), placed.map { it.column })
    }

    @Test
    fun nonOverlappingEventsKeepFullWidth() {
        val placed = layoutEvents(listOf(event("a", 60, 120), event("b", 120, 180)), layoutDay)
        assertEquals(listOf(1, 1), placed.map { it.totalColumns })
        assertEquals(listOf(0, 0), placed.map { it.column })
    }

    @Test
    fun overlappingEventsShareColumns() {
        val placed = layoutEvents(listOf(event("a", 60, 180), event("b", 90, 150)), layoutDay)
        assertEquals(2, placed.first().totalColumns)
        assertEquals(setOf(0, 1), placed.map { it.column }.toSet())
    }

    @Test
    fun backToBackEventReusesAFreedColumn() {
        // a and c overlap b's window but not each other, so c can reuse a's column.
        val placed = layoutEvents(listOf(event("a", 60, 120), event("b", 60, 240), event("c", 120, 180)), layoutDay)
        assertEquals(2, placed.first().totalColumns)
        assertEquals(0, placed.first { it.event.title == "a" }.column)
        assertEquals(1, placed.first { it.event.title == "b" }.column)
        assertEquals(0, placed.first { it.event.title == "c" }.column)
    }

    @Test
    fun separateClustersAreSizedIndependently() {
        val placed = layoutEvents(
            listOf(event("a", 60, 180), event("b", 90, 120), event("c", 300, 360), event("d", 320, 360)),
            layoutDay,
        )
        assertEquals(2, placed.first { it.event.title == "a" }.totalColumns)
        assertEquals(2, placed.first { it.event.title == "c" }.totalColumns)
    }

    @Test
    fun eventsAreOrderedByStartTime() {
        val placed = layoutEvents(listOf(event("late", 300, 360), event("early", 60, 120)), layoutDay)
        assertEquals(listOf("early", "late"), placed.map { it.event.title })
    }
}

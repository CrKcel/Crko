package com.crko

import androidx.compose.ui.unit.dp
import com.crko.domain.Event
import com.crko.ui.schedule.HourHeight
import com.crko.ui.schedule.hourLayoutOf
import kotlin.test.Test
import kotlin.test.assertEquals

private fun event(start: Int, end: Int) = Event("e", "event", "2026-10-30", start, end)

class TimelineHoursTest {

    @Test
    fun emptyHoursTakeHalfTheHeight() {
        val layout = hourLayoutOf(emptyList())
        assertEquals(HourHeight / 2, layout.offset(1))
        assertEquals(HourHeight * 12f, layout.totalHeight)
        assertEquals(150, layout.minutesAt(HourHeight * 1.25f))
        assertEquals(0, layout.minutesAt(0.dp))
        assertEquals(24 * 60 - 1, layout.minutesAt(layout.totalHeight))
    }

    @Test
    fun hoursHoldingAnEventKeepFullHeight() {
        // 09:00–10:00 fills only hour 9; the hours around it stay short.
        val layout = hourLayoutOf(listOf(event(9 * 60, 10 * 60)))
        assertEquals(HourHeight * 4.5f, layout.offset(9))
        assertEquals(HourHeight * 5.5f, layout.offset(10))
        assertEquals(HourHeight * 6f, layout.offset(11))
        assertEquals(HourHeight * 4.5f, layout.offsetOf(9 * 60))
        assertEquals(HourHeight * 5.5f, layout.offsetOf(10 * 60))
        assertEquals(HourHeight * 12.5f, layout.totalHeight)
    }

    @Test
    fun anEventSpanningHoursFillsEachOfThem() {
        val layout = hourLayoutOf(listOf(event(9 * 60 + 30, 11 * 60 + 30)))
        assertEquals(HourHeight * 7.5f, layout.offset(12))
        // Halfway through an occupied hour maps halfway into its full row.
        assertEquals(630, layout.minutesAt(layout.offset(10) + HourHeight / 2))
    }

    @Test
    fun anEventEndingOnTheHourLeavesTheNextHourEmpty() {
        val layout = hourLayoutOf(listOf(event(9 * 60, 10 * 60)))
        assertEquals(HourHeight * 6f, layout.offset(11))
    }

    @Test
    fun minutesAtClampsInsideTheBlock() {
        val layout = hourLayoutOf(emptyList())
        assertEquals(0, layout.minutesAt(-10.dp))
        assertEquals(24 * 60 - 1, layout.minutesAt(layout.totalHeight * 2f))
    }
}

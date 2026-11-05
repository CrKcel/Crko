package com.crko

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crko.ui.schedule.HourHeight
import com.crko.ui.schedule.HourLayout
import com.crko.ui.schedule.HourRatio
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineHoursTest {

    private val hourHeight = HourHeight * HourRatio

    /** Dp values drift in the last ulp through float association; compare with a small tolerance. */
    private fun assertClose(expected: Dp, actual: Dp) {
        assertTrue(abs(actual.value - expected.value) <= 0.01f, "expected $expected, was $actual")
    }

    @Test
    fun everyHourTakesOneRatioShareOfTheHeight() {
        assertClose(hourHeight, HourLayout.offset(1))
        assertClose(hourHeight * 24, HourLayout.totalHeight)
        assertEquals(150, HourLayout.minutesAt(hourHeight * 2.5f))
        assertEquals(0, HourLayout.minutesAt(0.dp))
        assertEquals(24 * 60 - 1, HourLayout.minutesAt(HourLayout.totalHeight))
    }

    @Test
    fun hourOffsetsStayUniformAcrossTheDay() {
        assertClose(hourHeight * 9, HourLayout.offset(9))
        assertClose(hourHeight * 10, HourLayout.offset(10))
        assertClose(hourHeight * 11, HourLayout.offset(11))
        assertClose(hourHeight * 9, HourLayout.offsetOf(9 * 60))
        assertClose(hourHeight * 10, HourLayout.offsetOf(10 * 60))
    }

    @Test
    fun halfAnHourIntoARowMapsToTheMiddleOfTheHour() {
        // Half an hour of height into the 10:00 row: 10:30, within a minute of rounding drift.
        val minutes = HourLayout.minutesAt(HourLayout.offset(10) + hourHeight / 2)
        assertTrue(abs(minutes - 630) <= 1, "expected 630, was $minutes")
    }

    @Test
    fun minutesAtClampsInsideTheBlock() {
        assertEquals(0, HourLayout.minutesAt(-10.dp))
        assertEquals(24 * 60 - 1, HourLayout.minutesAt(HourLayout.totalHeight * 2f))
    }
}

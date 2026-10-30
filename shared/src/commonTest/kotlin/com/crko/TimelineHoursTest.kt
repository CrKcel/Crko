package com.crko

import androidx.compose.ui.unit.dp
import com.crko.ui.schedule.HourHeight
import com.crko.ui.schedule.HourLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineHoursTest {

    @Test
    fun everyHourTakesHalfTheHeight() {
        assertEquals(HourHeight / 2, HourLayout.offset(1))
        assertEquals(HourHeight * 12f, HourLayout.totalHeight)
        assertEquals(150, HourLayout.minutesAt(HourHeight * 1.25f))
        assertEquals(0, HourLayout.minutesAt(0.dp))
        assertEquals(24 * 60 - 1, HourLayout.minutesAt(HourLayout.totalHeight))
    }

    @Test
    fun hourOffsetsStayUniformAcrossTheDay() {
        assertEquals(HourHeight * 4.5f, HourLayout.offset(9))
        assertEquals(HourHeight * 5f, HourLayout.offset(10))
        assertEquals(HourHeight * 5.5f, HourLayout.offset(11))
        assertEquals(HourHeight * 4.5f, HourLayout.offsetOf(9 * 60))
        assertEquals(HourHeight * 5f, HourLayout.offsetOf(10 * 60))
    }

    @Test
    fun halfAnHourIntoARowMapsToTheMiddleOfTheHour() {
        assertEquals(630, HourLayout.minutesAt(HourLayout.offset(10) + HourHeight / 4))
    }

    @Test
    fun minutesAtClampsInsideTheBlock() {
        assertEquals(0, HourLayout.minutesAt(-10.dp))
        assertEquals(24 * 60 - 1, HourLayout.minutesAt(HourLayout.totalHeight * 2f))
    }
}

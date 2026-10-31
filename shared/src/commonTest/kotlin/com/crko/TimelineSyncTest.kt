package com.crko

import com.crko.ui.schedule.TimelineSync
import com.crko.ui.schedule.timelineSyncAction
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineSyncTest {

    @Test
    fun scrollCrossingADayReportsTheBlockUnderTheTopEdge() {
        assertEquals(
            TimelineSync.Report,
            timelineSyncAction(block = 21, selectedBlock = 20, reportedBlock = 20, idle = false),
        )
    }

    @Test
    fun pickElsewhereFollowsItOnceTheScrollRests() {
        assertEquals(
            TimelineSync.Follow,
            timelineSyncAction(block = 20, selectedBlock = 25, reportedBlock = 20, idle = true),
        )
    }

    @Test
    fun pickElsewhereDuringAFlingIsNotOverwritten() {
        assertEquals(
            TimelineSync.None,
            timelineSyncAction(block = 20, selectedBlock = 25, reportedBlock = 20, idle = false),
        )
    }

    @Test
    fun scrollAlreadyOnTheSelectionDoesNothing() {
        assertEquals(
            TimelineSync.None,
            timelineSyncAction(block = 20, selectedBlock = 20, reportedBlock = 25, idle = true),
        )
    }
}

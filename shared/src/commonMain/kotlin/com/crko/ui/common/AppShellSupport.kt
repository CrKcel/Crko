package com.crko.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalClock
import com.crko.domain.MaxEndMinutes
import com.crko.state.AppStateHolder
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The event open in the editor, plus the occurrence being edited when it belongs to a series. */
internal data class EventEditorTarget(val event: Event, val occurrenceDate: LocalDate?)

/**
 * A fresh event starting at the next half hour, as a floating local time on [state]'s today. The ID
 * stays null: the state holder assigns one when the event is saved.
 */
internal fun newEvent(state: AppStateHolder, now: LocalClock): Event {
    val start = ((now.time.hour * 60 + now.time.minute) / 30 + 1) * 30
    val startMinutes = start.coerceAtMost(23 * 60 + 30)
    val endMinutes = (startMinutes + 60).coerceAtMost(MaxEndMinutes)
    val date = state.today
    return Event(
        id = null,
        title = "",
        start = IcsTimeValue.Floating(
            LocalDateTime(date, LocalTime(startMinutes / 60, startMinutes % 60)),
        ),
        end = IcsTimeValue.Floating(
            LocalDateTime(date, LocalTime(endMinutes / 60, endMinutes % 60)),
        ),
    )
}

/**
 * Ticks once a minute, on the minute boundary, and rolls the day over at midnight.
 *
 * The clock is exposed as a [State] rather than a value: the screens only read it where the time is
 * actually drawn or a tap needs it, so a tick repaints the time marker without recomposing the
 * schedule, its calendar or its timelines.
 */
@Composable
internal fun rememberClock(state: AppStateHolder): State<LocalClock> {
    val clock = remember { mutableStateOf(localClockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            val instant = Clock.System.now()
            delay((60 - (instant.epochSeconds % 60)).seconds)
            clock.value = localClockNow()
            state.refreshToday()
        }
    }
    return clock
}

private fun localClockNow(): LocalClock {
    val instant = Clock.System.now()
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return LocalClock(instant, local.date, local.time)
}

/**
 * One tab's page, kept composed while another tab is shown. Building a page costs tens of
 * milliseconds (the calendar and timeline lay out a lot), and composing it on the frame the bar's
 * selection bubble is released made that animation skip frames. Pages that are not on top are
 * skipped while drawing, so they stay out of the bar's backdrop, and they swallow touches so a tap
 * the visible page ignores cannot reach the page underneath.
 */
@Composable
internal fun TabPage(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .drawWithContent {
                if (visible) drawContent()
            }
            .then(
                if (visible) {
                    Modifier
                } else {
                    // Invisible to screen readers too; otherwise they would walk into a page the
                    // user cannot see.
                    Modifier
                        .clearAndSetSemantics { }
                        .swallowTouches()
                },
            ),
    ) {
        content()
    }
}

private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

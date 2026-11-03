package com.crko.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.AlarmTrigger
import com.crko.domain.IcsTimeValue
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.daysTo
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import com.crko.domain.relatedToEnd
import com.crko.domain.triggerMinutes
import com.crko.domain.wallDate
import com.crko.domain.withDeviceDateTime
import com.crko.state.EditScope
import com.crko.state.event.RepeatEndMode
import com.crko.state.event.buildRule
import com.crko.state.event.endMinutesAfterStartChange
import com.crko.state.event.minutesToTime
import com.crko.state.event.updateReminderAlarms
import com.crko.state.event.withRule
import com.crko.ui.transfer.CalendarFileBridge
import com.crko.ui.transfer.rememberCalendarFileBridge
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * Form state and draft rules for [EventEditorContent]. Holds every field the editor edits, derives
 * the validation flags the save button reads, and turns the draft back into an [Event]. Pure
 * helpers live in `com.crko.state.event.EventDraftRules`.
 */
@Stable
internal class EventEditorState(
    private val event: Event,
    private val occurrenceDate: LocalDate?,
    val fileBridge: CalendarFileBridge,
) {
    private val deviceZone = TimeZone.currentSystemDefault()
    private val eventStartLocal = event.localStart(deviceZone)
    private val eventEndLocal = event.localEnd(deviceZone)
    private val eventStartMinutes = eventStartLocal.hour * 60 + eventStartLocal.minute
    private val eventEndMinutes = eventEndLocal?.let { it.hour * 60 + it.minute } ?: eventStartMinutes
    private val initialStartDate = occurrenceDate ?: event.day
    private val initialEndDate = initialStartDate.plusDays(event.day.daysTo(event.endDay))
    // The editor edits only the first relative alarm; imported audio/email/absolute alarms survive.
    private val originalAlarm = event.alarms.firstOrNull { it.trigger is AlarmTrigger.Relative }
    private val originalReminder = originalAlarm?.triggerMinutes
    private val originalAtEnd = originalAlarm?.relatedToEnd ?: false

    var title by mutableStateOf(event.title)
    var note by mutableStateOf(event.note)
    var date by mutableStateOf(initialStartDate)
    var endDate by mutableStateOf(initialEndDate)
    var allDay by mutableStateOf(event.start is IcsTimeValue.Date)
    var start by mutableStateOf(minutesToTime(eventStartMinutes))
    var end by mutableStateOf(minutesToTime(eventEndMinutes))
    var repeatFrequency by mutableStateOf(event.repeat?.frequency)
    var repeatDays by mutableStateOf(
        event.repeat?.byDay?.filter { it.ordinal == 0 }?.map { it.weekday }?.toSet().orEmpty(),
    )
    var intervalText by mutableStateOf((event.repeat?.interval ?: 1).toString())
    var endMode by mutableStateOf(
        when {
            event.repeat?.count != null -> RepeatEndMode.Count
            event.repeat?.until != null -> RepeatEndMode.Until
            else -> RepeatEndMode.Never
        },
    )
    var countText by mutableStateOf((event.repeat?.count ?: 10).toString())
    var untilDate by mutableStateOf(event.repeat?.until?.wallDate ?: initialStartDate.plusDays(30))
    var location by mutableStateOf(event.location)
    var categories by mutableStateOf(event.categories.joinToString(", "))
    var url by mutableStateOf(event.url)
    var status by mutableStateOf(event.status)
    var transparency by mutableStateOf(event.transparency)
    var priority by mutableStateOf(event.priority)
    var showMoreOptions by mutableStateOf(
        event.id != null && (
            event.location.isNotBlank() ||
                event.categories.isNotEmpty() ||
                event.url.isNotBlank() ||
                event.status != EventStatus.Confirmed ||
                event.transparency != EventTransparency.Opaque ||
                event.priority != null
            ),
    )
    var repeatExpanded by mutableStateOf(false)
    var reminderExpanded by mutableStateOf(false)
    var reminder by mutableStateOf(originalReminder)
    var reminderAtEnd by mutableStateOf(originalAtEnd)
    var scope by mutableStateOf(
        if (event.repeat != null && occurrenceDate != null) EditScope.ThisEvent else EditScope.AllEvents,
    )
    var error by mutableStateOf<String?>(null)
    var showStartPicker by mutableStateOf(false)
    var showEndPicker by mutableStateOf(false)
    var showDatePicker by mutableStateOf(false)
    var showEndDatePicker by mutableStateOf(false)
    var showUntilDatePicker by mutableStateOf(false)
    var attachmentNotice by mutableStateOf<String?>(null)

    val startMinutes: Int get() = start.hour * 60 + start.minute
    val endMinutes: Int get() = end.hour * 60 + end.minute
    val resolvedEndDate: LocalDate get() = if (endDate < date) date else endDate
    val interval: Int get() = intervalText.toIntOrNull()?.takeIf { it > 0 } ?: 1
    private val repeatCount: Int? get() = countText.toIntOrNull()?.takeIf { it > 0 }
    val repeatValid: Boolean get() = repeatFrequency == null || endMode != RepeatEndMode.Count || repeatCount != null
    val timeValid: Boolean get() = allDay || resolvedEndDate > date || endMinutes >= startMinutes
    val valid: Boolean get() = title.isNotBlank() && timeValid && repeatValid
    val canScope: Boolean get() = event.repeat != null && occurrenceDate != null
    val selectedScope: EditScope get() = if (canScope) scope else EditScope.AllEvents

    val pickingTimeField: TimeField?
        get() = when {
            showStartPicker -> TimeField.START
            showEndPicker -> TimeField.END
            else -> null
        }

    fun onTitleChange(value: String) {
        title = value
        error = null
    }

    fun onAllDayChange(checked: Boolean) {
        allDay = checked
        if (!checked && resolvedEndDate == date && endMinutes <= startMinutes) {
            end = minutesToTime((startMinutes + 60).coerceAtMost(MaxEndMinutes))
        }
    }

    fun onDatePicked(picked: LocalDate) {
        date = picked
        if (endDate < picked) endDate = picked
    }

    fun onEndDatePicked(picked: LocalDate) {
        endDate = if (picked < date) date else picked
    }

    fun onUntilDatePicked(picked: LocalDate) {
        untilDate = picked
    }

    fun onStartTimePicked(picked: LocalTime) {
        // A zero-length event (RFC 5545: timed event without DTEND) moves as a point in time instead
        // of being stretched into an hour the user never asked for.
        val zeroDuration = !allDay && resolvedEndDate == date && startMinutes == endMinutes
        start = picked
        endMinutesAfterStartChange(zeroDuration, resolvedEndDate == date, picked.hour * 60 + picked.minute, endMinutes)
            ?.let { end = minutesToTime(it) }
    }

    fun onEndTimePicked(picked: LocalTime) {
        end = picked
        // Midnight or an earlier clock time on the start day means the event ends the next day.
        if (picked.hour * 60 + picked.minute <= startMinutes && resolvedEndDate == date) {
            endDate = date.plusDays(1)
        }
    }

    fun dismissTimePicker() {
        showStartPicker = false
        showEndPicker = false
    }

    fun onFrequencyChange(next: RepeatFrequency?) {
        repeatFrequency = next
        // A weekly rule with no explicit BYDAY follows the start weekday.
        if (next == RepeatFrequency.Weekly && repeatDays.isEmpty()) {
            repeatDays = setOf(date.mondayIndex)
        }
    }

    fun onToggleWeekday(index: Int) {
        repeatDays = if (index in repeatDays) repeatDays - index else repeatDays + index
    }

    fun onIntervalChange(value: Int) {
        intervalText = value.toString()
    }

    fun onCountTextChange(value: String) {
        countText = value.filter(Char::isDigit)
    }

    /** Builds the event to save from the current draft; callers supply the edit scope. */
    fun buildEvent(): Event {
        val startTime = LocalTime(
            start.hour,
            start.minute,
            if (startMinutes == eventStartMinutes) eventStartLocal.second else 0,
        )
        val endTime = LocalTime(
            end.hour,
            end.minute,
            if (endMinutes == eventEndMinutes) eventEndLocal?.second ?: eventStartLocal.second else 0,
        )
        val newStart: IcsTimeValue
        val newEnd: IcsTimeValue?
        if (allDay) {
            newStart = IcsTimeValue.Date(date)
            newEnd = IcsTimeValue.Date(resolvedEndDate.plusDays(1))
        } else {
            newStart = event.start.withDeviceDateTime(LocalDateTime(date, startTime), deviceZone)
            // Equal wall times on one day mean a zero-length event (no DTEND).
            val zeroDuration = resolvedEndDate == date && startTime == endTime
            newEnd = if (zeroDuration) {
                null
            } else {
                event.start.withDeviceDateTime(LocalDateTime(resolvedEndDate, endTime), deviceZone)
            }
        }
        return event.copy(
            title = title,
            note = note,
            start = newStart,
            end = newEnd,
            location = location,
            categories = categories.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
            url = url,
            status = status,
            transparency = transparency,
            priority = priority,
            alarms = updateReminderAlarms(
                event.alarms,
                originalReminder,
                reminder,
                originalAtEnd,
                reminderAtEnd,
            ),
            recurrence = event.recurrence.withRule(
                buildRule(
                    frequency = repeatFrequency,
                    old = event.repeat,
                    weekdays = repeatDays,
                    interval = interval,
                    endMode = endMode,
                    count = repeatCount,
                    until = untilDate,
                ),
            ),
        )
    }
}

@Composable
internal fun rememberEventEditorState(event: Event, occurrenceDate: LocalDate?): EventEditorState {
    val fileBridge = rememberCalendarFileBridge()
    // Re-key by the full event so switching between events resets every field.
    return remember(event, occurrenceDate) { EventEditorState(event, occurrenceDate, fileBridge) }
}

package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.RepeatFrequency
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import com.crko.i18n.Strings
import com.crko.i18n.alarmLabel
import com.crko.state.EditScope
import com.crko.state.event.RepeatEndMode
import com.crko.ui.icons.AppIcons
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun Caption(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
internal fun EndDateRow(
    strings: Strings,
    date: LocalDate,
    allDay: Boolean,
    onPickDate: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.endDate,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = strings.dayMonth(date),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPickDate)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (!allDay) {
            Text(
                text = strings.clear,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Low-emphasis action that turns a one-day event into a multi-day one. */
@Composable
internal fun AddEndDateRow(
    text: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = "+ $text",
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun TimeField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Row that reveals a secondary setting and shows its current value while collapsed. */
@Composable
internal fun ExpandableRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Icon(
            imageVector = AppIcons.Expand,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

internal fun repeatSummary(strings: Strings, frequency: RepeatFrequency?): String = when (frequency) {
    null -> strings.repeatNone
    RepeatFrequency.Daily -> strings.repeatDaily
    RepeatFrequency.Weekly -> strings.repeatWeekly
    RepeatFrequency.Monthly -> strings.repeatMonthly
    RepeatFrequency.Yearly -> strings.repeatYearly
}

internal fun reminderSummary(strings: Strings, reminder: Int?, atEnd: Boolean): String = when (reminder) {
    null -> strings.reminderNone
    else -> strings.alarmLabel(reminder, atEnd)
}

/** Compact stepper: the interval is a single line instead of two half-width buttons. */
@Composable
internal fun IntervalRow(
    label: String,
    interval: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        StepButton("−", enabled = interval > 1) { onChange((interval - 1).coerceAtLeast(1)) }
        Text(
            text = interval.toString(),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp),
        )
        StepButton("+") { onChange(interval + 1) }
    }
}

@Composable
internal fun StepButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.title3,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

/** Horizontal strip of dates: the selected day plus the surrounding week, with a month picker. */
@Composable
internal fun DateSection(
    strings: Strings,
    date: LocalDate,
    allDay: Boolean,
    onAllDayChange: (Boolean) -> Unit,
    onChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val days = remember(date) { date.startOfWeek().let { start -> (0 until 7).map { start.plusDays(it) } } }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.dayMonth(date),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = strings.selectDate,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickDate)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = strings.allDay,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(6.dp))
            Switch(checked = allDay, onCheckedChange = onAllDayChange)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEachIndexed { index, day ->
                val selected = day == date
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                        )
                        .clickable { onChange(day) }
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings.weekdaysShort[index],
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 10.sp,
                        color = if (selected) {
                            MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = day.day.toString(),
                        style = MiuixTheme.textStyles.body1,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RepeatSection(
    strings: Strings,
    frequency: RepeatFrequency?,
    weekdays: Set<Int>,
    onFrequencyChange: (RepeatFrequency?) -> Unit,
    onToggleWeekday: (Int) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(strings.repeatNone, frequency == null, { onFrequencyChange(null) }, Modifier.weight(1f))
            RepeatChip(
                strings.repeatDaily,
                frequency == RepeatFrequency.Daily,
                { onFrequencyChange(RepeatFrequency.Daily) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.repeatWeekly,
                frequency == RepeatFrequency.Weekly,
                { onFrequencyChange(RepeatFrequency.Weekly) },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(
                strings.repeatMonthly,
                frequency == RepeatFrequency.Monthly,
                { onFrequencyChange(RepeatFrequency.Monthly) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.repeatYearly,
                frequency == RepeatFrequency.Yearly,
                { onFrequencyChange(RepeatFrequency.Yearly) },
                Modifier.weight(1f),
            )
        }
        // Custom weekdays are just `FREQ=WEEKLY;BYDAY=…`, so they live on the weekly rule.
        if (frequency == RepeatFrequency.Weekly) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                strings.weekdaysFull.forEachIndexed { index, label ->
                    RepeatChip(label, index in weekdays, { onToggleWeekday(index) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Frequency chips plus the interval and end-condition controls shown while "repeat" is expanded. */
@Composable
internal fun RepeatSettings(
    state: EventEditorState,
    strings: Strings,
) {
    RepeatSection(
        strings = strings,
        frequency = state.repeatFrequency,
        weekdays = state.repeatDays,
        onFrequencyChange = state::onFrequencyChange,
        onToggleWeekday = state::onToggleWeekday,
    )
    if (state.repeatFrequency == null) return
    Spacer(Modifier.height(12.dp))
    IntervalRow(
        label = strings.repeatInterval,
        interval = state.interval,
        onChange = state::onIntervalChange,
    )
    Spacer(Modifier.height(10.dp))
    Caption(strings.repeatEnd)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RepeatChip(strings.repeatNeverEnds, state.endMode == RepeatEndMode.Never, {
            state.endMode = RepeatEndMode.Never
        }, Modifier.weight(1f))
        RepeatChip(strings.repeatAfterTimes, state.endMode == RepeatEndMode.Count, {
            state.endMode = RepeatEndMode.Count
        }, Modifier.weight(1f))
        RepeatChip(strings.repeatUntilDate, state.endMode == RepeatEndMode.Until, {
            state.endMode = RepeatEndMode.Until
        }, Modifier.weight(1f))
    }
    when (state.endMode) {
        RepeatEndMode.Count -> {
            Spacer(Modifier.height(6.dp))
            TextField(
                value = state.countText,
                onValueChange = state::onCountTextChange,
                label = strings.repeatAfterTimes,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RepeatEndMode.Until -> {
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.dayMonth(state.untilDate),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.primaryContainer)
                    .clickable { state.showUntilDatePicker = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        RepeatEndMode.Never -> Unit
    }
}

@Composable
internal fun ReminderSection(
    strings: Strings,
    reminder: Int?,
    atEnd: Boolean,
    onMinutesChange: (Int?) -> Unit,
    onBaseChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(strings.reminderNone, reminder == null, { onMinutesChange(null) }, Modifier.weight(1f))
            RepeatChip(
                strings.alarmLabel(0, atEnd),
                reminder == 0,
                { onMinutesChange(0) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.alarmLabel(5, atEnd),
                reminder == 5,
                { onMinutesChange(5) },
                Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(
                strings.alarmLabel(15, atEnd),
                reminder == 15,
                { onMinutesChange(15) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.alarmLabel(30, atEnd),
                reminder == 30,
                { onMinutesChange(30) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.alarmLabel(60, atEnd),
                reminder == 60,
                { onMinutesChange(60) },
                Modifier.weight(1f),
            )
        }
        // The presets are minutes relative to a base; imported `RELATED=END` triggers must be able
        // to keep it, and a start-relative reminder can be switched to fire relative to the end.
        if (reminder != null) {
            Spacer(Modifier.height(6.dp))
            Caption(strings.reminderRelativeTo)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.reminderRelativeToStart, !atEnd, { onBaseChange(false) }, Modifier.weight(1f))
                RepeatChip(strings.reminderRelativeToEnd, atEnd, { onBaseChange(true) }, Modifier.weight(1f))
            }
        }
    }
}

/** Edit-scope chips shown when a single occurrence of a repeating event is edited. */
@Composable
internal fun EditScopeSection(
    strings: Strings,
    scope: EditScope,
    onScopeChange: (EditScope) -> Unit,
) {
    Caption(strings.editScope)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RepeatChip(strings.scopeThisEvent, scope == EditScope.ThisEvent, {
            onScopeChange(EditScope.ThisEvent)
        }, Modifier.weight(1f))
        RepeatChip(strings.scopeThisAndFuture, scope == EditScope.ThisAndFuture, {
            onScopeChange(EditScope.ThisAndFuture)
        }, Modifier.weight(1f))
        RepeatChip(strings.scopeAllEvents, scope == EditScope.AllEvents, {
            onScopeChange(EditScope.AllEvents)
        }, Modifier.weight(1f))
    }
}

@Composable
internal fun RepeatChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

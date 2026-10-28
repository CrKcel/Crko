package com.crko.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crko.domain.Event
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val HourHeight = 56.dp
internal const val SlotMinutes = 30
internal const val SlotsPerHour = 60 / SlotMinutes
internal val SlotHeight = HourHeight / SlotsPerHour
internal val TimeGutterWidth = 40.dp
internal val ColumnHorizontalPadding = 6.dp
internal val MonthRowHeight = 54.dp

internal fun isOverdue(todo: Todo, today: LocalDate): Boolean =
    !todo.done && todo.due != null && LocalDate.parse(todo.due) < today

/** Tones for event blocks, derived from the palette so they follow the theme. */
@Composable
internal fun eventTones(): List<Pair<Color, Color>> {
    val scheme = MiuixTheme.colorScheme
    return listOf(
        scheme.primary to scheme.onPrimary,
        scheme.secondaryVariant to scheme.onSecondaryVariant,
        scheme.tertiaryContainerVariant to scheme.onTertiaryContainer,
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
}

/** Stable per-event tone assignment so colors do not shift when other events change. */
internal fun eventToneIndex(event: Event, count: Int): Int {
    if (count <= 0) return 0
    val key = event.id ?: event.title
    var hash = 7
    for (c in key) hash = hash * 31 + c.code
    return (hash and Int.MAX_VALUE) % count
}

@Composable
internal fun priorityColor(priority: TodoPriority): Color = when (priority) {
    TodoPriority.High -> MiuixTheme.colorScheme.error
    TodoPriority.Normal -> MiuixTheme.colorScheme.primary
    TodoPriority.Low -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

internal fun timeRangeText(event: Event, strings: Strings): String =
    strings.timeRange(
        kotlinx.datetime.LocalTime(event.startMinutes / 60, event.startMinutes % 60),
        kotlinx.datetime.LocalTime(event.endMinutes / 60, event.endMinutes % 60),
    )

/** Compact "09:00–10:00" label used inside timeline blocks where the full format is clipped. */
internal fun clockRange(event: Event): String {
    fun fmt(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h:00" else "$h:${m.toString().padStart(2, '0')}"
    }
    return "${fmt(event.startMinutes)}–${fmt(event.endMinutes)}"
}

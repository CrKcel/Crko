package com.crko.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crko.domain.Event
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.daysTo
import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
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
internal val WeekDayHeaderHeight = 38.dp

/** Heading that opens a day inside the scrolling timeline. */
internal val TimelineDayHeadingHeight = 34.dp

/**
 * Virtual page counts large enough for any real trip through the calendar. Both axes start in the
 * middle so there is room on either side, and a jump clamps instead of wrapping.
 */
internal const val DayBlockCount = 20001
internal const val WeekBlockCount = 2400

/**
 * Index space of a scroller that keeps running past the ends of the calendar: index
 * [center] is the block starting at the base date and every other index a fixed offset from it.
 * Mapping never depends on what is currently on screen, so scrolling or paging never remaps content
 * and a fling is never rewritten while it is in flight.
 */
internal class BlockAxis(val baseDate: LocalDate, val blockDays: Int, val pageCount: Int) {
    val center: Int get() = pageCount / 2

    /** First date of the block at [index]. */
    fun dateAt(index: Int): LocalDate = baseDate.plusDays((index - center) * blockDays)

    /** Block holding [date], which need not be the first day of that block. */
    fun indexOf(date: LocalDate): Int =
        (center + baseDate.daysTo(date).floorDiv(blockDays)).coerceIn(0, pageCount - 1)

    /**
     * The date the block at [index] stands for once the scroller rests on it: its own day, or in a
     * week axis the same weekday [weekdayOf] falls on. [indexOf] maps this back to the same index.
     */
    fun restingDateAt(index: Int, weekdayOf: LocalDate): LocalDate {
        val first = dateAt(index)
        val shift = (weekdayOf.mondayIndex - first.mondayIndex + blockDays) % blockDays
        return first.plusDays(shift)
    }
}

/**
 * Axis whose base date is saved with the screen, so a restored scroller index still resolves to the
 * block it was showing. Weeks start on Monday, so the base of the week axis is snapped there.
 */
@Composable
internal fun rememberBlockAxis(base: LocalDate, weeks: Boolean): BlockAxis {
    val saved = rememberSaveable { (if (weeks) base.startOfWeek() else base).toString() }
    return remember(saved, weeks) {
        val date = LocalDate.parse(saved)
        if (weeks) BlockAxis(date, 7, WeekBlockCount) else BlockAxis(date, 1, DayBlockCount)
    }
}

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

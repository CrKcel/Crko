package com.crko.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
internal const val SlotMinutes = 60
internal val TimeGutterWidth = 40.dp
internal val ColumnHorizontalPadding = 6.dp
internal val MonthRowHeight = 54.dp

/** Heading that opens a day inside the scrolling timeline. */
internal val TimelineDayHeadingHeight = 34.dp

/** Share of a full [HourHeight] one hour of a timeline block takes up. */
private const val HourRatio = 0.5f

/**
 * Vertical geometry of the 24 hours of one timeline block. Every hour takes the same half
 * [HourHeight], so a day occupies twelve full hours whether or not anything is scheduled on it.
 */
internal object HourLayout {
    val totalHeight: Dp = HourHeight * (24 * HourRatio)

    /** Top edge of [hour]'s row; 24 resolves to the bottom of the grid. */
    fun offset(hour: Int): Dp = HourHeight * (hour.coerceIn(0, 24) * HourRatio)

    /** Vertical position of [minutes] after midnight. */
    fun offsetOf(minutes: Int): Dp =
        HourHeight * (minutes.coerceIn(0, 24 * 60) / 60f * HourRatio)

    /** Minute of day under [y], rounded down and kept inside the block. */
    fun minutesAt(y: Dp): Int =
        (y / HourHeight / HourRatio * 60f).toInt().coerceIn(0, 24 * 60 - 1)
}

/**
 * Virtual page counts large enough for any real trip through the calendar. Both axes start in the
 * middle so there is room on either side, and a jump clamps instead of wrapping.
 */
internal const val DayBlockCount = 20001
internal const val WeekBlockCount = 2400

/**
 * Days the timeline scroller spans — about eleven years in all, five either side of where it
 * opened. The day view registers a heading and a grid per day so the heading can stay pinned, and
 * a window this wide keeps that item list cheap to rebuild while still outlasting any real scroll.
 */
internal const val TimelineDayBlockCount = 4001

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
        if (weeks) BlockAxis(date, 7, WeekBlockCount) else BlockAxis(date, 1, TimelineDayBlockCount)
    }
}

internal fun isOverdue(todo: Todo, today: LocalDate): Boolean =
    !todo.done && todo.due != null && LocalDate.parse(todo.due) < today

/** Light grey rule that closes each hour cell in the day and week grids. */
@Composable
internal fun hourDividerColor(): Color = MiuixTheme.colorScheme.dividerLine

/** Tones for event blocks, derived from the palette so they follow the theme. */
@Composable
internal fun eventTones(): List<Pair<Color, Color>> {
    val scheme = MiuixTheme.colorScheme
    // Keyed on the colors, not the scheme: miuix mutates one Colors instance in place, so its
    // identity never changes. Caching keeps every event block and week column from allocating the
    // same six pairs again, and gives the rows one stable list instance to compare against.
    return remember(
        scheme.primary, scheme.onPrimary,
        scheme.secondaryVariant, scheme.onSecondaryVariant,
        scheme.surfaceContainerHighest, scheme.onSurfaceContainerHighest,
        scheme.primaryContainer, scheme.onPrimaryContainer,
        scheme.secondaryContainer, scheme.onSurfaceContainer,
        scheme.tertiaryContainer, scheme.onTertiaryContainer,
    ) {
        listOf(
            scheme.primary to scheme.onPrimary,
            scheme.secondaryVariant to scheme.onSecondaryVariant,
            // Miuix maps tertiaryContainerVariant onto onTertiaryContainer under Monet, which would set the
            // block background to the exact colour of its text; surface tiers stay paired in every palette.
            scheme.surfaceContainerHighest to scheme.onSurfaceContainerHighest,
            scheme.primaryContainer to scheme.onPrimaryContainer,
            // miuix's static scheme paints onSecondaryContainer as a muted label, not as text on its
            // own container; onSurfaceContainer is a foreground in both the static and Monet mappings.
            scheme.secondaryContainer to scheme.onSurfaceContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
        )
    }
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

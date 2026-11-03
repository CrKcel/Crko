package com.crko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crko.domain.Calendar
import com.crko.domain.DefaultCalendarColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Accent of a calendar row and of its chip; an unset colour uses the app's default seed. */
internal fun Calendar.accentColor(): Color = Color((colorArgb ?: DefaultCalendarColor).toInt())

/** A dot showing [calendar]'s accent. */
@Composable
internal fun CalendarDot(calendar: Calendar, size: Int = 12) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(calendar.accentColor()),
    )
}

/**
 * Horizontal chips that pick the calendar of a new record. Shown only when more than one calendar
 * exists; the caller keeps the selected id in its own state.
 */
@Composable
internal fun CalendarChips(
    calendars: List<Calendar>,
    selectedId: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        calendars.forEach { calendar ->
            val selected = calendar.id == selectedId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            MiuixTheme.colorScheme.primaryContainer
                        } else {
                            MiuixTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onSelect(calendar.id) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CalendarDot(calendar, size = 9)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = calendar.name,
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

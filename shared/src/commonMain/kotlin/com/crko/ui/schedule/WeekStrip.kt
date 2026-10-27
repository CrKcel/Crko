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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WeekStrip(
    state: AppStateHolder,
    strings: Strings,
    weekStart: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dates = remember(weekStart) { weekStart.weekDates() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        dates.forEachIndexed { index, date ->
            DayChip(
                label = strings.weekdaysShort[index],
                dayOfMonth = date.day,
                selected = date == selected,
                isToday = date == today,
                hasEvents = state.appState.events.any { it.date == date.toString() },
                onClick = { onSelect(date) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayChip(
    label: String,
    dayOfMonth: Int,
    selected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = if (label.length > 1) 10.sp else 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> MiuixTheme.colorScheme.primary
                        isToday -> MiuixTheme.colorScheme.primaryContainer
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayOfMonth.toString(),
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp,
                color = when {
                    selected -> MiuixTheme.colorScheme.onPrimary
                    isToday -> MiuixTheme.colorScheme.onPrimaryContainer
                    else -> MiuixTheme.colorScheme.onSurface
                },
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    if (hasEvents) {
                        if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                    } else Color.Transparent,
                ),
        )
    }
}

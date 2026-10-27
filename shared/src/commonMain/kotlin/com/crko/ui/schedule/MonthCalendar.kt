package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.monthGrid
import com.crko.domain.plusMonths
import com.crko.domain.startOfMonth
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PageCount = 2400
private const val HomePage = PageCount / 2

/**
 * Full-month grid for [monthAnchor]. Swiping pages through adjacent months; every page change is
 * reported through [onMonthChange] so the parent can keep the header and week strip in sync.
 */
@Composable
internal fun MonthCalendar(
    eventDates: Set<String>,
    strings: Strings,
    monthAnchor: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchorMonth = monthAnchor.startOfMonth()
    val pagerState = rememberPagerState(initialPage = HomePage) { PageCount }

    // Selection outside the pager (week strip, today) recenters the pager on the anchor month.
    LaunchedEffect(anchorMonth) {
        val pageMonth = anchorMonth.plusMonths(pagerState.currentPage - HomePage)
        if (pageMonth != anchorMonth) pagerState.scrollToPage(HomePage)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != HomePage) onMonthChange(anchorMonth.plusMonths(page - HomePage))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            strings.weekdaysShort.forEachIndexed { index, label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (index >= 5) 0.6f else 1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        MonthCaption(
            strings = strings,
            month = anchorMonth.plusMonths(pagerState.currentPage - HomePage),
            fraction = pagerState.currentPageOffsetFraction,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            MonthPage(
                month = anchorMonth.plusMonths(page - HomePage),
                days = anchorMonth.plusMonths(page - HomePage).monthGrid(),
                selected = selected,
                today = today,
                eventDates = eventDates,
                onSelect = onSelect,
            )
        }
    }
}

/**
 * Month label that fades between pages. The outgoing label leaves as the pager offset grows so the
 * title follows the finger instead of jumping when the page settles.
 */
@Composable
private fun MonthCaption(
    strings: Strings,
    month: LocalDate,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val alpha = (1f - abs(fraction) * 2f).coerceIn(0.35f, 1f)
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = strings.monthTitle(month.year, month.month),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
        )
    }
}

@Composable
private fun MonthPage(
    month: LocalDate,
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    eventDates: Set<String>,
    onSelect: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    MonthCell(
                        date = date,
                        inMonth = date.month == month.month && date.year == month.year,
                        selected = date == selected,
                        isToday = date == today,
                        hasEvents = date.toString() in eventDates,
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .padding(vertical = 1.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> scheme.primary
                        isToday -> scheme.primaryContainer
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MiuixTheme.textStyles.body1,
                fontSize = 14.sp,
                fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    selected -> scheme.onPrimary
                    isToday -> scheme.onPrimaryContainer
                    inMonth -> scheme.onSurface
                    else -> scheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
                },
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !hasEvents || !inMonth -> Color.Transparent
                        selected -> scheme.primary
                        else -> scheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                    },
                ),
        )
    }
}


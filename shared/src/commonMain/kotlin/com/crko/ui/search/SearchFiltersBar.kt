package com.crko.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.crko.domain.Calendar
import com.crko.i18n.Strings
import com.crko.state.search.SearchType
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Label of one record kind in the type chips and in the result group headers. */
internal fun searchTypeLabel(strings: Strings, type: SearchType): String = when (type) {
    SearchType.Events -> strings.searchTypeEvents
    SearchType.Todos -> strings.searchTypeTodos
    SearchType.Journals -> strings.searchTypeJournals
}

/**
 * Type, calendar, category and date filters of the search panel. Every row is horizontally
 * scrollable, so a long calendar or category list stays one line tall inside the dialog.
 */
@Composable
internal fun SearchFiltersBar(
    strings: Strings,
    types: Set<SearchType>,
    onToggleType: (SearchType) -> Unit,
    calendars: List<Calendar>,
    calendarId: String?,
    onCalendar: (String?) -> Unit,
    categories: List<String>,
    category: String?,
    onCategory: (String?) -> Unit,
    from: LocalDate?,
    to: LocalDate?,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onClearDates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ChipsRow {
            SearchType.entries.forEach { type ->
                SearchChip(
                    text = searchTypeLabel(strings, type),
                    selected = type in types,
                    onClick = { onToggleType(type) },
                )
            }
        }
        ChipsRow {
            SearchChip(
                text = strings.searchAllCalendars,
                selected = calendarId == null,
                onClick = { onCalendar(null) },
            )
            calendars.forEach { calendar ->
                SearchChip(
                    text = calendar.name,
                    selected = calendar.id == calendarId,
                    onClick = { onCalendar(calendar.id) },
                )
            }
        }
        if (categories.isNotEmpty()) {
            ChipsRow {
                SearchChip(
                    text = strings.searchAllCategories,
                    selected = category == null,
                    onClick = { onCategory(null) },
                )
                categories.forEach { name ->
                    SearchChip(
                        text = name,
                        selected = name == category,
                        onClick = { onCategory(name) },
                    )
                }
            }
        }
        ChipsRow {
            SearchChip(
                text = from?.let(strings::dayMonth) ?: strings.searchDateFrom,
                selected = from != null,
                onClick = onPickFrom,
            )
            SearchChip(
                text = to?.let(strings::dayMonth) ?: strings.searchDateTo,
                selected = to != null,
                onClick = onPickTo,
            )
            if (from != null || to != null) {
                SearchChip(text = strings.clear, selected = false, onClick = onClearDates)
            }
        }
    }
}

@Composable
private fun ChipsRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun SearchChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        maxLines = 1,
        // Paired surface/content colors from the theme: unselected chips read dark on gray, and a
        // selected chip is solid primary with its on-color, so the state survives both palettes.
        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceContainerHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

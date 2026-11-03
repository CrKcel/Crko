package com.crko.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.search.MaxSearchResults
import com.crko.state.search.SearchFilters
import com.crko.state.search.SearchHit
import com.crko.state.search.SearchType
import com.crko.ui.schedule.DatePickerDialog
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Which end of the date window the open picker edits. */
private enum class SearchDateField { From, To }

/**
 * Search panel: one query field, the filter rows and the grouped results. The query runs through
 * [AppStateHolder.search], i.e. in the state layer; this dialog only renders what comes back and
 * hands the tapped hit to [onOpenHit].
 */
@Composable
internal fun SearchDialog(
    show: Boolean,
    state: AppStateHolder,
    strings: Strings,
    onDismiss: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
) {
    if (!show) return
    var query by remember { mutableStateOf("") }
    var types by remember { mutableStateOf(SearchType.entries.toSet()) }
    var calendarId by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableStateOf<LocalDate?>(null) }
    var to by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf<SearchDateField?>(null) }

    val filters = SearchFilters(types = types, calendarId = calendarId, category = category, from = from, to = to)
    val hits = state.search(query, filters)
    // The category chips come from the records, so they follow imports and edits of the calendar.
    val categories = remember(state.appState) { state.searchCategories() }

    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.search) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = strings.searchHint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SearchFiltersBar(
                strings = strings,
                types = types,
                onToggleType = { type -> types = if (type in types) types - type else types + type },
                calendars = state.appState.visibleCalendars,
                calendarId = calendarId,
                onCalendar = { calendarId = it },
                categories = categories,
                category = category,
                onCategory = { category = it },
                from = from,
                to = to,
                onPickFrom = { picking = SearchDateField.From },
                onPickTo = { picking = SearchDateField.To },
                onClearDates = {
                    from = null
                    to = null
                },
            )
            Spacer(Modifier.height(6.dp))
            when {
                query.isBlank() -> SearchHint(strings.searchEmpty)
                hits.isEmpty() -> SearchHint(strings.searchNoResults)
                else -> {
                    SearchResultsList(hits = hits, strings = strings, onOpen = onOpenHit)
                    if (hits.size >= MaxSearchResults) {
                        Text(
                            text = strings.searchLimitReached,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    picking?.let { field ->
        val current = if (field == SearchDateField.From) from else to
        DatePickerDialog(
            initial = current ?: state.today,
            today = state.today,
            strings = strings,
            onDismiss = { picking = null },
            onConfirm = { picked ->
                if (field == SearchDateField.From) from = picked else to = picked
                picking = null
            },
        )
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

package com.crko.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.DefaultCalendarColor
import com.crko.i18n.Strings
import com.crko.state.search.SearchHit
import com.crko.state.search.SearchRecord
import com.crko.state.search.SearchType
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Results grouped by record kind. Ordering comes from the state layer already (kind, date,
 * priority, title), so the groups are only split here and never re-sorted in the UI.
 */
@Composable
internal fun SearchResultsList(
    hits: List<SearchHit>,
    strings: Strings,
    onOpen: (SearchHit) -> Unit,
) {
    val groups = SearchType.entries.mapNotNull { type ->
        hits.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp),
    ) {
        groups.forEach { (type, group) ->
            item(key = "header-${type.name}") {
                Row(
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = searchTypeLabel(strings, type),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.items(group.size),
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            itemsIndexed(group) { _, hit ->
                SearchResultRow(
                    hit = hit,
                    strings = strings,
                    onClick = { onOpen(hit) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, strings: Strings, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color((hit.calendarColor ?: DefaultCalendarColor).toInt())),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = hit.title.ifBlank { strings.untitledEvent },
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                hit.date?.let(strings::dayMonth),
                hit.calendarName,
                strings.searchRecurring.takeIf { hit.recurring },
                searchPreview(hit),
            ).joinToString(" · ")
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** First non-blank line of the record's place or notes, as the one-line result preview. */
private fun searchPreview(hit: SearchHit): String = when (val record = hit.record) {
    is SearchRecord.EventRecord -> record.event.location.ifBlank { record.event.note }
    is SearchRecord.TodoRecord -> record.todo.location.ifBlank { record.todo.note }
    is SearchRecord.JournalRecord -> record.journal.note
}.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

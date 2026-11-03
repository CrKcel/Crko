package com.crko.state.search

import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.displayDate
import com.crko.domain.matchesSearch
import com.crko.domain.matchesSearchCategories
import com.crko.domain.occursWithin
import com.crko.domain.partOfSeries
import com.crko.domain.searchQueryText
import com.crko.domain.todoPriorityRank
import com.crko.state.StateAccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Record kinds the global search covers; the UI uses the order for its grouping. */
enum class SearchType { Events, Todos, Journals }

/**
 * Narrowing applied on top of the text query. [types] is the set of record kinds to search, and
 * [calendarId] / [category] restrict the result to one calendar or one category when non-null.
 * [from] and [to] bound the date window and are inclusive; either may be null for "no bound".
 */
data class SearchFilters(
    val types: Set<SearchType> = SearchType.entries.toSet(),
    val calendarId: String? = null,
    val category: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/** The record a [SearchHit] points at; the UI opens the matching editor from it. */
sealed interface SearchRecord {
    data class EventRecord(val event: Event) : SearchRecord
    data class TodoRecord(val todo: Todo) : SearchRecord
    data class JournalRecord(val journal: Journal) : SearchRecord
}

/**
 * One search result. [recurring] is true for a series master or one of its instances; the label is
 * picked by the UI from [SearchType] and this flag. A repeating series appears once, at its master
 * record, and is never expanded into its (possibly endless) instances.
 */
data class SearchHit(
    val record: SearchRecord,
    val type: SearchType,
    val calendarId: String,
    val calendarName: String,
    val calendarColor: Long?,
    val title: String,
    val date: LocalDate?,
    val recurring: Boolean,
    /** Sort rank of a todo's `PRIORITY`; zero for records without one. */
    val priorityRank: Int,
)

/** Upper bound of one search result set; the UI says so when it is hit. */
const val MaxSearchResults = 100

/** Ordering inside one result set: kind, date, todo priority, title. */
private val SearchHitOrder: Comparator<SearchHit> =
    compareBy<SearchHit> { it.type.ordinal }
        .thenBy { it.date == null }
        .thenBy { it.date?.toEpochDays() ?: Long.MAX_VALUE }
        .thenBy { it.priorityRank }
        .thenBy { it.title.lowercase() }

/**
 * Read-only search across every visible calendar. Records are matched on their own fields through
 * [com.crko.domain.matchesSearch]; a date window asks each record's own occurrence projection, so a
 * repeating series is tested day by day inside the window instead of being expanded beforehand.
 */
internal class SearchService(private val access: StateAccess) {

    /** Hits for [query] under [filters], capped at [MaxSearchResults] and sorted for display. */
    fun search(query: String?, filters: SearchFilters = SearchFilters()): List<SearchHit> {
        val needle = searchQueryText(query) ?: return emptyList()
        val from = filters.from
        val to = filters.to
        if (from != null && to != null && from > to) return emptyList()
        val categories = filters.category?.let { setOf(it) }.orEmpty()
        val zone = TimeZone.currentSystemDefault()
        val hits = mutableListOf<SearchHit>()
        for (calendar in access.state.visibleCalendars) {
            if (filters.calendarId != null && calendar.id != filters.calendarId) continue
            if (SearchType.Events in filters.types) {
                for (event in calendar.events) {
                    if (!event.matchesSearch(needle)) continue
                    if (!event.matchesSearchCategories(categories)) continue
                    if (!event.occursWithin(from, to, zone)) continue
                    hits += eventHit(calendar, event)
                }
            }
            if (SearchType.Todos in filters.types) {
                for (todo in calendar.todos) {
                    if (!todo.matchesSearch(needle)) continue
                    if (!todo.matchesSearchCategories(categories)) continue
                    if (!todo.occursWithin(from, to)) continue
                    hits += todoHit(calendar, todo)
                }
            }
            if (SearchType.Journals in filters.types) {
                for (journal in calendar.journals) {
                    if (!journal.matchesSearch(needle)) continue
                    if (!journal.matchesSearchCategories(categories)) continue
                    if (!journal.occursWithin(from, to, zone)) continue
                    hits += journalHit(calendar, journal)
                }
            }
        }
        return hits.sortedWith(SearchHitOrder).take(MaxSearchResults)
    }

    /**
     * Distinct categories of the visible records, for the category filter. Compared
     * case-insensitively so "Work" and "work" share one chip; [limit] keeps the chip row short.
     */
    fun categories(limit: Int = 20): List<String> =
        access.state.visibleCalendars
            .flatMap { calendar ->
                calendar.events.flatMap { it.categories } +
                    calendar.todos.flatMap { it.categories } +
                    calendar.journals.flatMap { it.categories }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .take(limit)

    private fun eventHit(calendar: Calendar, event: Event) = SearchHit(
        record = SearchRecord.EventRecord(event),
        type = SearchType.Events,
        calendarId = calendar.id,
        calendarName = calendar.name,
        calendarColor = calendar.colorArgb,
        title = event.title,
        date = event.day,
        recurring = event.partOfSeries,
        priorityRank = 0,
    )

    private fun todoHit(calendar: Calendar, todo: Todo) = SearchHit(
        record = SearchRecord.TodoRecord(todo),
        type = SearchType.Todos,
        calendarId = calendar.id,
        calendarName = calendar.name,
        calendarColor = calendar.colorArgb,
        title = todo.title,
        date = todo.dueDay ?: todo.startDay,
        recurring = todo.partOfSeries,
        priorityRank = todoPriorityRank(todo.priority),
    )

    private fun journalHit(calendar: Calendar, journal: Journal) = SearchHit(
        record = SearchRecord.JournalRecord(journal),
        type = SearchType.Journals,
        calendarId = calendar.id,
        calendarName = calendar.name,
        calendarColor = calendar.colorArgb,
        title = journal.title,
        date = journal.start?.displayDate(TimeZone.currentSystemDefault()),
        recurring = journal.partOfSeries,
        priorityRank = 0,
    )
}

package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.toIcsPriority
import com.crko.migration.legacyEvent
import com.crko.state.search.SearchFilters
import com.crko.state.search.SearchRecord
import com.crko.state.search.SearchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class SearchTest {

    private fun fixture(vararg calendars: Calendar): Fixture =
        Fixture(MemoryStorage(state = AppState(calendars = calendars.toList())))

    private fun calendar(
        id: String,
        name: String = id,
        events: List<Event> = emptyList(),
        todos: List<Todo> = emptyList(),
        journals: List<Journal> = emptyList(),
        visible: Boolean = true,
    ): Calendar = Calendar(
        id = id,
        name = name,
        visible = visible,
        events = events,
        todos = todos,
        journals = journals,
    )

    private fun date(text: String): LocalDate = LocalDate.parse(text)

    private fun event(
        id: String,
        title: String,
        day: String,
        note: String = "",
        location: String = "",
        categories: List<String> = emptyList(),
        attendees: List<String> = emptyList(),
        organizer: String? = null,
        url: String = "",
        icsUid: String? = null,
        repeat: RepeatRule? = null,
    ): Event = legacyEvent(
        id = id,
        title = title,
        date = day,
        startMinutes = 540,
        endMinutes = 600,
        note = note,
        location = location,
        categories = categories,
        attendees = attendees,
        organizer = organizer,
        url = url,
        icsUid = icsUid,
        repeat = repeat,
    )

    private fun todo(
        id: String,
        title: String,
        due: String? = null,
        note: String = "",
        location: String = "",
        categories: List<String> = emptyList(),
        priority: Int? = null,
        repeat: RepeatRule? = null,
    ): Todo = Todo(
        id = id,
        title = title,
        due = due?.let { IcsTimeValue.Date(date(it)) },
        note = note,
        location = location,
        categories = categories,
        priority = priority,
        repeat = repeat,
    )

    @Test
    fun chineseSubstringMatchesTitleNoteAndLocation() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(
                    event("e1", "项目会议", "2026-11-02", note = "讨论预算"),
                    event("e2", "晚饭", "2026-11-02", location = "会议室A"),
                    event("e3", "散步", "2026-11-03"),
                ),
                todos = listOf(todo("t1", "准备会议材料")),
                journals = listOf(Journal(id = "j1", title = "会议记录", note = "纪要")),
            ),
        )

        val hits = f.holder.search("会议")
        assertEquals(4, hits.size)
        assertEquals(
            setOf("项目会议", "晚饭", "准备会议材料", "会议记录"),
            hits.map { it.title }.toSet(),
        )
        assertEquals(
            listOf(SearchType.Events, SearchType.Todos, SearchType.Journals),
            hits.map { it.type }.distinct(),
        )
        // A note is searched like a title, and so is a place.
        assertEquals(listOf("项目会议"), f.holder.search("预算").map { it.title })
        assertEquals(listOf("晚饭"), f.holder.search("会议室").map { it.title })
    }

    @Test
    fun englishSearchIgnoresCase() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(event("e1", "Team Meeting", "2026-11-02", note = "Quarterly PLAN")),
            ),
        )

        assertEquals(1, f.holder.search("meeting").size)
        assertEquals(1, f.holder.search("MEETING").size)
        assertEquals(1, f.holder.search("MeEtInG").size)
        assertEquals(1, f.holder.search("plan").size)
        assertEquals(1, f.holder.search("PLAN").size)
        assertEquals(0, f.holder.search("weekly").size)
    }

    @Test
    fun notesPlacesCategoriesPeopleAndUidAreSearched() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(
                    event(
                        "e1",
                        "Review",
                        "2026-11-02",
                        note = "预算评审",
                        location = "会议室B",
                        categories = listOf("Work", "Finance"),
                        attendees = listOf("mailto:jane@example.com"),
                        organizer = "mailto:boss@example.com",
                        url = "https://example.com/agenda",
                        icsUid = "uid-abc-123",
                    ),
                ),
                todos = listOf(
                    todo(
                        "t1",
                        "Report",
                        note = "quarterly deck",
                        location = "Office",
                        categories = listOf("Finance"),
                    ),
                ),
                journals = listOf(Journal(id = "j1", title = "Notes", note = "评审结论")),
            ),
        )

        assertEquals(1, f.holder.search("预算评审").size)
        assertEquals(1, f.holder.search("会议室b").size)
        // One event and one todo carry the category.
        assertEquals(2, f.holder.search("finance").size)
        assertEquals(1, f.holder.search("jane@").size)
        assertEquals(1, f.holder.search("BOSS@").size)
        assertEquals(1, f.holder.search("UID-ABC").size)
        assertEquals(1, f.holder.search("agenda").size)
        assertEquals(listOf("Report"), f.holder.search("office").map { it.title })
        assertEquals(listOf("Notes"), f.holder.search("评审结论").map { it.title })
    }

    @Test
    fun categoryFilterNarrowsResults() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(
                    event("e1", "Review work", "2026-11-02", categories = listOf("Work")),
                    event("e2", "Review home", "2026-11-03", categories = listOf("Personal")),
                ),
            ),
        )

        assertEquals(2, f.holder.search("review").size)
        val work = f.holder.search("review", SearchFilters(category = "work"))
        assertEquals(listOf("Review work"), work.map { it.title })
        assertEquals(0, f.holder.search("review", SearchFilters(category = "Missing")).size)
    }

    @Test
    fun dateWindowMatchesOneOffAndRepeatingRecords() {
        val daily = RepeatRule(frequency = RepeatFrequency.Daily, count = 30)
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(
                    event("e1", "Alpha one", "2026-11-10"),
                    event("e2", "Alpha two", "2026-12-01"),
                    event("e3", "Alpha daily", "2026-11-01", repeat = daily),
                ),
                todos = listOf(todo("t1", "Alpha todo", due = "2026-11-20")),
                journals = listOf(
                    Journal(id = "j1", title = "Alpha journal", start = IcsTimeValue.Date(date("2026-11-20"))),
                ),
            ),
        )

        val early = f.holder.search("Alpha", SearchFilters(from = date("2026-11-05"), to = date("2026-11-06")))
        assertEquals(listOf("Alpha daily"), early.map { it.title })

        val oneDay = f.holder.search("Alpha", SearchFilters(from = date("2026-11-10"), to = date("2026-11-10")))
        assertEquals(setOf("Alpha daily", "Alpha one"), oneDay.map { it.title }.toSet())

        val december = f.holder.search("Alpha", SearchFilters(from = date("2026-12-01"), to = date("2026-12-01")))
        assertEquals(listOf("Alpha two"), december.map { it.title })

        // The daily series stops on 2026-11-30 through its COUNT.
        val ended = f.holder.search("Alpha", SearchFilters(from = date("2026-11-25"), to = date("2026-11-27")))
        assertEquals(listOf("Alpha daily"), ended.map { it.title })

        val todoWindow = f.holder.search(
            "Alpha todo",
            SearchFilters(from = date("2026-11-01"), to = date("2026-11-30")),
        )
        assertEquals(1, todoWindow.size)
        assertEquals(0, f.holder.search("Alpha todo", SearchFilters(from = date("2026-12-01"), to = date("2026-12-31"))).size)

        val journalWindow = f.holder.search(
            "Alpha journal",
            SearchFilters(from = date("2026-11-20"), to = date("2026-11-20")),
        )
        assertEquals(1, journalWindow.size)
    }

    @Test
    fun aRepeatingEventIsOneHitAndMarkedAsRecurring() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(
                    event(
                        "e1",
                        "Standup",
                        "2026-11-01",
                        repeat = RepeatRule(frequency = RepeatFrequency.Daily),
                    ),
                    event("e2", "One-off standup", "2026-11-02"),
                ),
            ),
        )

        val hits = f.holder.search("standup")
        assertEquals(2, hits.size)
        assertTrue(hits.single { it.title == "Standup" }.recurring)
        assertFalse(hits.single { it.title == "One-off standup" }.recurring)
        // The endless series stays a single master record, never expanded into instances.
        assertEquals(1, hits.count { it.title == "Standup" })
    }

    @Test
    fun resultsAreCappedAtOneHundred() {
        val events = (0 until 150).map { index ->
            event("e$index", "Bulk $index", "2026-11-02")
        }
        val f = fixture(calendar(id = "local", events = events))

        val hits = f.holder.search("Bulk")
        assertEquals(100, hits.size)
        assertEquals("Bulk 0", hits.first().title)
    }

    @Test
    fun blankQueryAndNoMatchReturnNothing() {
        val f = fixture(calendar(id = "local", events = listOf(event("e1", "Anything", "2026-11-02"))))

        assertTrue(f.holder.search("").isEmpty())
        assertTrue(f.holder.search("   ").isEmpty())
        assertTrue(f.holder.search(null).isEmpty())
        assertTrue(f.holder.search("no such text").isEmpty())
    }

    @Test
    fun calendarAndTypeFiltersNarrowResults() {
        val f = fixture(
            calendar(
                id = "home",
                events = listOf(event("e1", "Shared review", "2026-11-02")),
            ),
            calendar(
                id = "work",
                name = "Work",
                todos = listOf(todo("t1", "Shared review")),
                journals = listOf(Journal(id = "j1", title = "Shared review")),
            ),
            calendar(
                id = "hidden",
                events = listOf(event("e2", "Shared review", "2026-11-02")),
                visible = false,
            ),
        )

        // A hidden calendar keeps its records but leaves them out of the search.
        assertEquals(3, f.holder.search("shared").size)
        assertEquals(
            listOf(SearchType.Todos, SearchType.Journals),
            f.holder.search("shared", SearchFilters(calendarId = "work")).map { it.type },
        )
        assertEquals(
            listOf(SearchType.Events),
            f.holder.search("shared", SearchFilters(types = setOf(SearchType.Events))).map { it.type },
        )
        assertTrue(f.holder.search("shared", SearchFilters(types = emptySet())).isEmpty())
    }

    @Test
    fun resultsSortByKindThenDateThenTodoPriority() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(
                    event("e2", "Task event", "2026-11-05"),
                    event("e1", "Task event", "2026-11-01"),
                ),
                todos = listOf(
                    Todo(
                        id = "t-low",
                        title = "Task todo",
                        due = IcsTimeValue.Date(date("2026-11-02")),
                        priority = TodoPriority.Low.toIcsPriority(),
                    ),
                    Todo(
                        id = "t-high",
                        title = "Task todo",
                        due = IcsTimeValue.Date(date("2026-11-02")),
                        priority = TodoPriority.High.toIcsPriority(),
                    ),
                ),
            ),
        )

        val hits = f.holder.search("Task")
        assertEquals(
            listOf(SearchType.Events, SearchType.Events, SearchType.Todos, SearchType.Todos),
            hits.map { it.type },
        )
        assertEquals(listOf("2026-11-01", "2026-11-05"), hits.take(2).map { it.date.toString() })
        // Same due date: the high-priority todo comes first.
        val todoPriorities = hits.drop(2).map { (it.record as SearchRecord.TodoRecord).todo.priority }
        assertEquals(listOf(1, 9), todoPriorities)
    }

    @Test
    fun categoriesForTheFilterComeFromVisibleRecords() {
        val f = fixture(
            calendar(
                id = "local",
                events = listOf(event("e1", "A", "2026-11-02", categories = listOf("Work", "work"))),
                todos = listOf(todo("t1", "B", categories = listOf("Home"))),
            ),
        )

        assertEquals(listOf("Home", "Work"), f.holder.searchCategories())
    }
}

package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalCalendarId
import com.crko.domain.Todo
import com.crko.state.search.MaxSearchResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.datetime.LocalDate

/**
 * Reference measurement for the full scan behind the search panel: 20k events plus 5k todos on the
 * JVM host. The printed value is the number to quote; the assertion is deliberately loose so a slow
 * or shared CI machine cannot make the suite flaky.
 */
class SearchPerformanceTest {

    @Test
    fun fullScanOfTwentyThousandEventsAndFiveThousandTodosStaysWithinBudget() {
        val base = LocalDate(2025, 1, 1).toEpochDays().toInt()
        val events = (0 until 20_000).map { index ->
            testEvent(
                id = "event-$index",
                title = if (index % 100 == 0) "Weekly 会议 $index" else "Event $index",
                date = LocalDate.fromEpochDays(base + index % 1_000).toString(),
                startMinutes = 540,
                endMinutes = 600,
                note = "note $index",
                location = "Room ${index % 50}",
            )
        }
        val todos = (0 until 5_000).map { index ->
            Todo(
                id = "todo-$index",
                title = if (index % 100 == 0) "会议待办 $index" else "Todo $index",
                due = IcsTimeValue.Date(LocalDate.fromEpochDays(base + index % 500)),
                note = "todo note $index",
            )
        }
        val f = Fixture(
            MemoryStorage(
                state = AppState(
                    calendars = listOf(
                        Calendar(id = LocalCalendarId, name = "Crko", events = events, todos = todos),
                    ),
                ),
            ),
        )

        // Warm-up: the first call pays JIT and lazy-initialization costs this test is not about.
        assertEquals(20_000, f.holder.appState.events.size)
        assertEquals(5_000, f.holder.appState.todos.size)
        f.holder.search("会议")
        val mark = TimeSource.Monotonic.markNow()
        val hits = f.holder.search("会议")
        val elapsed = mark.elapsedNow()
        println("search 20k events + 5k todos: ${elapsed.inWholeMilliseconds} ms (${hits.size} hits)")

        assertEquals(MaxSearchResults, hits.size)
        assertTrue(elapsed < 5.seconds, "search took $elapsed")
    }
}

package com.crko.state.stats

import com.crko.state.StateAccess
import kotlinx.datetime.LocalDate

/** Read-only statistics; the date comes from the facade so a midnight rollover is followed. */
internal class StatsService(
    private val access: StateAccess,
    private val today: () -> LocalDate,
) {
    fun completedToday(): Int =
        access.state.visibleTodos.count { it.done && it.completedAt == today().toString() }
}

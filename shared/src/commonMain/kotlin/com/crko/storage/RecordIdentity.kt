package com.crko.storage

import com.crko.domain.AppState

/**
 * Gives every persisted record a stable id before the state reaches memory or SQLite. Todos and
 * journals already require an id in the domain model; events can still arrive from an import
 * without one. The repository and the migrator call the same function once, so a record without an
 * id never turns into a new database row on every save.
 */
internal fun AppState.ensureRecordIds(idProvider: () -> String): AppState {
    if (calendars.none { calendar -> calendar.events.any { it.id == null } }) return this
    return copy(
        calendars = calendars.map { calendar ->
            calendar.copy(
                events = calendar.events.map { event ->
                    event.id?.let { event } ?: event.copy(id = idProvider())
                },
            )
        },
    )
}

package com.crko.state

import com.crko.domain.AppState

/**
 * Who drove a state change: a user edit, whose records queue for upload to writable CalDAV
 * calendars, or the sync engine itself, whose records already mirror the server.
 */
enum class ChangeOrigin {
    User,
    Sync,
}

/**
 * The slice of the app state facade that extracted services may use. A service depends on this
 * interface instead of the facade itself, so it cannot reach into UI or platform concerns.
 */
internal interface StateAccess {
    val state: AppState
    fun persist(next: AppState, origin: ChangeOrigin = ChangeOrigin.User)
    fun notify(message: AppMessage)

    /** Calendar new records go into; null falls back to the first local calendar. */
    val defaultCalendarId: String?
    fun setDefaultCalendarId(id: String?)
}

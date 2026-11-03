package com.crko.state

import com.crko.domain.AppState

/**
 * The slice of the app state facade that extracted services may use. A service depends on this
 * interface instead of the facade itself, so it cannot reach into UI or platform concerns.
 */
internal interface StateAccess {
    val state: AppState
    fun persist(next: AppState)
    fun notify(message: AppMessage)

    /** Calendar new records go into; null falls back to the first local calendar. */
    val defaultCalendarId: String?
    fun setDefaultCalendarId(id: String?)
}

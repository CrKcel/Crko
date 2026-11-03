package com.crko.state.calendar

import com.crko.domain.Calendar
import com.crko.state.StateAccess

/** Why a calendar could not be deleted; the UI explains each case next to the confirmation. */
enum class CalendarDeleteResult { Success, LastLocalCalendar, NotFound }

/**
 * Management of local calendar containers: create, rename, recolour, hide, delete and choose the
 * default. Every mutation is one [StateAccess.persist]; records stay inside their own calendar.
 */
internal class CalendarService(
    private val access: StateAccess,
    private val idProvider: () -> String,
) {
    fun createLocalCalendar(name: String, color: Long?): Calendar? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val calendar = Calendar(
            id = idProvider(),
            name = trimmed,
            colorArgb = color,
        )
        access.persist(access.state.copy(calendars = access.state.calendars + calendar))
        return calendar
    }

    fun renameCalendar(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return update(id) { it.copy(name = trimmed) }
    }

    /**
     * Removes a local calendar together with the records it owns. The last local calendar stays,
     * because new records need somewhere to go. Attachment payloads are shared storage and are not
     * deleted here: another record may still reference the same file.
     */
    fun deleteCalendar(id: String): CalendarDeleteResult {
        val calendar = access.state.calendars.firstOrNull { it.id == id }
            ?: return CalendarDeleteResult.NotFound
        if (!calendar.isLocal) return CalendarDeleteResult.NotFound
        if (access.state.calendars.count { it.isLocal } <= 1) return CalendarDeleteResult.LastLocalCalendar
        access.persist(access.state.copy(calendars = access.state.calendars.filterNot { it.id == id }))
        if (access.defaultCalendarId == id) access.setDefaultCalendarId(null)
        return CalendarDeleteResult.Success
    }

    fun setCalendarVisible(id: String, visible: Boolean): Boolean = update(id) { it.copy(visible = visible) }

    /** Null restores the theme's fallback colour. */
    fun setCalendarColor(id: String, color: Long?): Boolean = update(id) { it.copy(colorArgb = color) }

    /** A subscription cannot be the default: records written into it would vanish on the next sync. */
    fun setDefaultCalendar(id: String): Boolean {
        val calendar = access.state.calendars.firstOrNull { it.id == id } ?: return false
        if (!calendar.isLocal) return false
        access.setDefaultCalendarId(id)
        return true
    }

    private fun update(id: String, transform: (Calendar) -> Calendar): Boolean {
        val index = access.state.calendars.indexOfFirst { it.id == id }
        if (index < 0) return false
        val calendars = access.state.calendars.toMutableList()
        calendars[index] = transform(calendars[index])
        access.persist(access.state.copy(calendars = calendars))
        return true
    }
}

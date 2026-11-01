package com.crko.state

import com.crko.domain.AppState
import com.crko.i18n.Strings

/**
 * Platform bridge that turns [planReminders] output into real system notifications. Null on
 * platforms where notifications are unavailable (tests, hosts without the plumbing).
 */
interface ReminderScheduler {
    /**
     * Replaces everything currently scheduled with the triggers derived from [state]. Skipped
     * silently when notifications are not permitted.
     */
    suspend fun reschedule(state: AppState, strings: Strings)

    /**
     * Asks the user for notification permission when the platform has not decided yet. Returns
     * whether notifications are allowed afterwards; a previously denied request is not repeated.
     */
    suspend fun ensurePermission(): Boolean
}

expect fun reminderScheduler(): ReminderScheduler?

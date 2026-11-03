package com.crko.state

/**
 * The delta one scheduling pass hands to the platform: reminders whose key disappeared, and
 * reminders that are new or whose fingerprint changed. A changed key is only re-registered — both
 * platforms replace the alarm/request registered under the same key — so it needs no cancel.
 */
data class ReminderDiff(
    val cancelKeys: Set<String>,
    val register: List<ScheduledReminder>,
) {
    val isEmpty: Boolean get() = cancelKeys.isEmpty() && register.isEmpty()
}

/**
 * Compares the fingerprints of the plan the platform last confirmed against a freshly computed
 * one. Unchanged keys are kept untouched, so an edit that moves one record produces a delta with
 * only that record's keys, and a change that alters no reminder (journals, theme, language)
 * produces nothing at all.
 */
fun diffReminders(
    previous: Map<String, ReminderFingerprint>,
    current: List<ScheduledReminder>,
): ReminderDiff {
    val currentKeys = HashSet<String>(current.size * 2)
    current.forEach { currentKeys.add(it.key) }
    return ReminderDiff(
        cancelKeys = previous.keys.filterNotTo(HashSet()) { it in currentKeys },
        register = current.filter { previous[it.key] != it.fingerprint },
    )
}

/**
 * Platform bridge that turns the reminder plan computed by the state layer into real system
 * notifications. The state layer diffs consecutive plans and hands over only the delta; the
 * implementation registers and cancels strictly by key. Null on platforms where notifications are
 * unavailable (tests, hosts without the plumbing).
 */
interface ReminderScheduler {
    /**
     * Cancels the keys in [cancelKeys] and registers [register], replacing whatever was registered
     * under the same key before. Returns whether the platform took the change; `false` (notifications
     * not permitted) makes the next pass treat the whole plan as new instead of assuming it applied.
     */
    suspend fun execute(cancelKeys: Set<String>, register: List<ScheduledReminder>): Boolean

    /**
     * Replaces everything currently scheduled with [plan] without consulting what was registered
     * before. The fallback path for process start, boot restore and recovery; also the answer to a
     * bookkeeping state the diff cannot trust.
     */
    suspend fun replaceAll(plan: List<ScheduledReminder>): Boolean

    /**
     * Asks the user for notification permission when the platform has not decided yet. Returns
     * whether notifications are allowed afterwards; a previously denied request is not repeated.
     */
    suspend fun ensurePermission(): Boolean
}

/** The platform bridge, or null where notifications have no plumbing (tests, bare hosts). */
expect fun reminderScheduler(): ReminderScheduler?

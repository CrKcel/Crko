package com.crko.state

/** What one import changed; the feedback names every kind, so a journal-only file is not "nothing". */
data class ImportOutcome(
    val events: Int = 0,
    val todos: Int = 0,
    val journals: Int = 0,
    /** Local records removed: cancelled by the file, or gone from a re-imported source. */
    val removed: Int = 0,
) {
    val isEmpty: Boolean get() = events == 0 && todos == 0 && journals == 0 && removed == 0
}

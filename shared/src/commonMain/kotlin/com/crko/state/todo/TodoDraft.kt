package com.crko.state.todo

import com.crko.domain.TodoPriority
import kotlinx.datetime.LocalDate

/**
 * Editable fields of a todo. A draft always carries every field the editor owns, so a null date or
 * a blank string clears that field instead of leaving the stored value; fields outside this type
 * (`UID`, `RELATED-TO`, attachments, recurrence) are never part of an edit and stay unchanged.
 */
data class TodoDraft(
    val title: String,
    val note: String = "",
    val location: String = "",
    val start: LocalDate? = null,
    val due: LocalDate? = null,
    val priority: TodoPriority = TodoPriority.Normal,
    val percentComplete: Int = 0,
    val categories: List<String> = emptyList(),
)

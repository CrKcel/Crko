package com.crko.i18n

import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsWarning
import com.crko.domain.SubscriptionFailure
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month

/** English strings; also the fallback for every language other than Chinese. */
object EnStrings : Strings {
    override val appName = "Schedule"
    override val tabSchedule = "Schedule"
    override val tabTodo = "Todo"
    override val tabMore = "More"

    override val today = "Today"
    override val tomorrow = "Tomorrow"
    override val yesterday = "Yesterday"
    override val weekdaysFull = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    override val weekdaysShort = listOf("M", "T", "W", "T", "F", "S", "S")

    private val monthFull = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    // Short form keeps the header on one line next to the week and toolbar actions.
    override fun monthTitle(year: Int, month: Month) = "${monthFull[month.ordinal].take(3)} $year"
    override fun dayMonth(date: LocalDate) = "${monthFull[date.month.ordinal].take(3)} ${date.day}"
    override fun monthName(month: Month) = monthFull[month.ordinal].take(3)
    override fun time(time: LocalTime) = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    override fun timeRange(start: LocalTime, end: LocalTime) = "${time(start)} – ${time(end)}"
    override fun relativeDay(date: LocalDate, delta: Int) = when (delta) {
        0 -> today
        1 -> tomorrow
        -1 -> yesterday
        else -> weekdaysFull[date.dayOfWeek.ordinal]
    }

    override val eventNew = "New event"
    override val eventEdit = "Edit event"
    override val eventDetail = "Event details"
    override val eventTitleField = "Title"
    override val noteField = "Notes"
    override val save = "Save"
    override val cancel = "Cancel"
    override val edit = "Edit"
    override val close = "Close"
    override val done = "Done"
    override val saved = "Saved"
    override val deleted = "Deleted"
    override val undo = "Undo"
    override val deleteUndone = "Delete undone"
    override val deleteUndoFailed = "Couldn't undo: the record exists again or its calendar is gone"
    override val todoAdded = "Todo added"
    override val delete = "Delete"
    override val startTime = "Start"
    override val endTime = "End"
    override val invalidTimeRange = "End time must be after start time"
    override val emptyTitle = "Title is required"
    override val noEventsToday = "Nothing scheduled today"
    override val noEventsSelected = "Nothing scheduled"
    override val expandCalendar = "Show month"
    override val collapseCalendar = "Hide month"
    override val scheduleHint = "Tap an empty slot to add"
    override fun eventsCount(count: Int) = if (count == 0) "" else if (count == 1) "1 event" else "$count events"
    override val switchView = "Switch view"
    override val viewTimeline = "Timeline"
    override val viewList = "List"
    override val dayView = "Day view"
    override val weekOverview = "Week overview"
    override val repeat = "Repeat"
    override val repeatNone = "Never"
    override val repeatDaily = "Daily"
    override val repeatWeekly = "Weekly"
    override val repeatMonthly = "Monthly"
    override val repeatYearly = "Yearly"
    override val repeatCustom = "Custom"
    override val repeatWeekdaysRequired = "Pick at least one day"
    override val repeatInterval = "Every"
    override val repeatEnd = "Ends"
    override val repeatNeverEnds = "Never"
    override val repeatAfterTimes = "After"
    override val repeatUntilDate = "On date"

    override val allDay = "All day"
    override val endDate = "End date"
    override val location = "Location"
    override val locationField = "Location"
    override val categories = "Categories"
    override val categoriesField = "Categories (comma separated)"
    override val urlField = "URL"
    override val attachments = "Attachments"
    override val attachmentSaveFailed = "The attachment could not be saved"
    override val attachmentBroken = "The attachment data could not be decoded"
    override val moreOptions = "More options"
    override val eventStatus = "Status"
    override val statusConfirmed = "Confirmed"
    override val statusTentative = "Tentative"
    override val statusCancelled = "Cancelled"
    override val transparency = "Availability"
    override val busy = "Busy"
    override val free = "Free"
    override val priorityNone = "None"
    override val reminder = "Reminder"
    override val reminderNone = "None"
    override val reminderAtTime = "At start"
    override fun reminderBefore(minutes: Int) = "$minutes min before"
    override fun reminderAfterStart(minutes: Int) = "$minutes min after start"
    override val reminderAtEnd = "At end"
    override fun reminderBeforeEnd(minutes: Int) = "$minutes min before end"
    override fun reminderAfterEnd(minutes: Int) = "$minutes min after end"
    override val reminderRelativeTo = "Based on"
    override val reminderRelativeToStart = "Start"
    override val reminderRelativeToEnd = "End"
    override val editScope = "Apply to"
    override val scopeThisEvent = "This event"
    override val scopeThisAndFuture = "This and later"
    override val scopeAllEvents = "All events"

    override val todos = "Todo"
    override val todoNew = "New todo"
    override val todoEdit = "Edit todo"
    override val todoDetail = "Todo details"
    override val todoTitleField = "What needs doing"
    override val untitledTodo = "Untitled todo"
    override val startDate = "Start date"
    override val progress = "Progress"
    override val priority = "Priority"
    override val priorityHigh = "High"
    override val priorityNormal = "Medium"
    override val priorityLow = "Low"
    override val dueDate = "Due date"
    override val clear = "Clear"
    override val customDate = "Custom"
    override val selectDate = "Select date"
    override val incomplete = "To do"
    override val completed = "Completed"
    override val todoStatusInProgress = "In progress"
    override val todoStatusCancelled = "Cancelled"
    override val emptyTodos = "No todos"
    override val todoActions = "Todo actions"
    override val todoCompleteSeries = "Complete series"
    override val todoStopRecurrence = "Stop repeating"
    override fun items(count: Int) = if (count == 1) "1 item" else "$count items"
    override val subtaskAdd = "Add subtask"
    override fun subtaskOf(parent: String) = "Subtask of \"$parent\""
    override val journals = "Journals"
    override val journalNew = "New journal"
    override val journalEdit = "Edit journal"
    override val journalEmpty = "No journals yet"
    override val journalDate = "Date"
    override val search = "Search"
    override val searchHint = "Search events, todos, journals"
    override val searchNoResults = "No matches"
    override val searchFilters = "Filters"
    override val searchAllCalendars = "All calendars"
    override val searchAllCategories = "All categories"
    override val searchDateFrom = "From"
    override val searchDateTo = "To"
    override val searchRecurring = "Repeats"
    override val searchLimitReached = "Showing the first 100 results"
    override val searchTypeEvents = "Events"
    override val searchTypeTodos = "Todos"
    override val searchTypeJournals = "Journals"
    override val statistics = "Statistics"
    override val settings = "Settings"
    override val calendarsAndData = "Calendars & Data"
    override val back = "Back"
    override val completedToday = "Done today"
    override val activeTodos = "Active"
    override val upcomingEvents = "Upcoming"
    override val last7Days = "Last 7 days"
    override val byPriority = "By priority"
    override val thisWeek = "This week"
    override val todayEvents = "Today"
    override val calendarData = "Calendar data"
    override val importIcs = "Import ICS file"
    override val importIcsHint = "Bring events in from another calendar app"
    override val exportIcs = "Export ICS file"
    override val exportIcsHint = "Save every event as an .ics file"
    override val nothingToImport = "Nothing to import"
    override val importFailed = "Import failed. Check the file format."
    override val exportFailed = "Export failed"
    override val exported = "Exported"
    override fun importedItems(events: Int, todos: Int, journals: Int, removed: Int): String {
        val items = listOf(
            events.takeIf { it > 0 }?.let { if (it == 1) "1 event" else "$it events" },
            todos.takeIf { it > 0 }?.let { if (it == 1) "1 todo" else "$it todos" },
            journals.takeIf { it > 0 }?.let { if (it == 1) "1 journal" else "$it journals" },
        ).filterNotNull()
        val imported = when {
            items.isEmpty() -> ""
            items.size == 1 -> "${items[0]} imported"
            else -> "${items.dropLast(1).joinToString(", ")} and ${items.last()} imported"
        }
        val removedText = if (removed > 0) {
            if (removed == 1) "1 record removed" else "$removed records removed"
        } else {
            ""
        }
        return listOf(imported, removedText).filter { it.isNotEmpty() }.joinToString("; ")
    }
    override fun importFailure(failure: IcsImportFailure): String = when (failure) {
        IcsImportFailure.TooLarge -> "The file is larger than 5 MB. Export a shorter date range and try again."
        IcsImportFailure.InvalidEncoding -> "The file encoding could not be recognized. Export it again as UTF-8 or GBK."
        IcsImportFailure.Unreadable -> "The selected file could not be read"
        IcsImportFailure.InvalidFormat -> importFailed
    }
    override fun importWarning(warning: IcsWarning): String = when (warning) {
        IcsWarning.UnresolvedTimeZone -> "Some time zones were unknown; those events use local time"
        IcsWarning.InvalidValue -> "Some dates or times were invalid and were skipped"
        IcsWarning.UnsupportedRecurrence -> "Some recurrence rules are unsupported; those events were simplified"
        IcsWarning.MultipleRrules -> "An event had several recurrence rules; only the first was used"
        IcsWarning.MissingStart -> "Some events had no usable start date and were skipped"
        IcsWarning.AmbiguousRecurrenceId -> "Several changes shared one occurrence date; they are kept as separate events"
        IcsWarning.ExruleIgnored -> "Some events contain deprecated EXRULE exclusions, which were ignored"
        IcsWarning.UnknownSubcomponent -> "Some events contain unsupported subcomponents, which were ignored"
        IcsWarning.ExdateMismatch -> "Some excluded dates did not match the event start time and were ignored"
        IcsWarning.UnappliedRecurrenceRange -> "Some \"this and future\" exceptions carry no recurrence of their own; later instances still follow the original rule"
    }
    override fun importWarningLocation(line: Int?, record: String?): String = when {
        record != null && line != null -> " ($record, line $line)"
        record != null -> " ($record)"
        line != null -> " (line $line)"
        else -> ""
    }
    override fun importWarningMore(count: Int): String = " ($count more)"
    override fun storageRecovered(droppedRecords: Int): String =
        if (droppedRecords > 0) {
            "Stored calendar data was damaged; $droppedRecords records could not be read. The original file was kept as a backup."
        } else {
            "Stored calendar data could not be read. The original file was kept as a backup."
        }
    override val untitledEvent = "Untitled event"
    override val importPreviewTitle = "Choose what to import"
    override val importPreviewEvent = "Event"
    override val importPreviewTodo = "Todo"
    override val importPreviewJournal = "Journal"
    override val importPreviewRemove = "Remove cancelled events"
    override val importPreviewEmpty = "The file has no records to choose from"
    override val importSelectAll = "Select all"
    override val importSelectNone = "Clear"
    override val importConfirm = "Import selected"
    override val subscriptions = "Subscriptions"
    override val subscriptionsHint = "Sync webcal feeds or CalDAV calendars"
    override val subscriptionAdd = "Add subscription"
    override val subscriptionAddHint = "Paste a calendar link (webcal:// or https://)"
    override val subscriptionName = "Name"
    override val subscriptionUrl = "URL"
    override val subscriptionUrlInvalid = "Invalid URL. Use a full http(s) or webcal address."
    override val subscriptionSyncNow = "Sync now"
    override val subscriptionNeverSynced = "Never synced"
    override val subscriptionsEmpty = "No subscriptions"
    override fun subscriptionLastSynced(at: String) = "Last synced $at"
    override fun subscriptionSynced(name: String, events: Int, todos: Int, removed: Int): String {
        val items = listOf(
            events.takeIf { it > 0 }?.let { if (it == 1) "1 event" else "$it events" },
            todos.takeIf { it > 0 }?.let { if (it == 1) "1 todo" else "$it todos" },
        ).filterNotNull()
        val changed = if (items.isEmpty()) "" else ", updated ${items.joinToString(", ")}"
        val removedText = if (removed > 0) ", removed $removed" else ""
        return "\"$name\" synced$changed$removedText"
    }
    override fun subscriptionUnchanged(name: String) = "\"$name\" is up to date"
    override fun subscriptionFailed(name: String, failure: SubscriptionFailure): String = when (failure) {
        SubscriptionFailure.Unreachable -> "Could not sync \"$name\": the server is unreachable"
        SubscriptionFailure.Unauthorized -> "Could not sync \"$name\": the server requires sign-in"
        SubscriptionFailure.HttpError -> "Could not sync \"$name\": the server returned an error"
        SubscriptionFailure.NotCalendar -> "Could not sync \"$name\": the address is not a calendar"
        SubscriptionFailure.TooLarge -> "Could not sync \"$name\": the calendar is too large"
    }
    override val calendars = "Calendars"
    override val calendar = "Calendar"
    override val calendarAdd = "New calendar"
    override val calendarAddHint = "Separate work, personal and family events"
    override val calendarName = "Name"
    override val calendarColor = "Color"
    override val calendarDefault = "Default"
    override val calendarSetDefault = "Set as default"
    override val calendarHidden = "Hidden"
    override val calendarExport = "Export this calendar"
    override val calendarRename = "Rename calendar"
    override val calendarDelete = "Delete calendar"
    override fun calendarDeleteSummary(name: String, records: Int) =
        if (records == 1) {
            "\"$name\" and its 1 record will be deleted permanently."
        } else {
            "\"$name\" and its $records records will be deleted permanently."
        }
    override fun partStatName(partStat: IcsPartStat): String = when (partStat) {
        IcsPartStat.Accepted -> "accepted"
        IcsPartStat.Declined -> "declined"
        IcsPartStat.Tentative -> "tentatively accepted"
        IcsPartStat.Delegated -> "delegated"
        IcsPartStat.Completed -> "completed"
        IcsPartStat.InProcess -> "in progress"
        IcsPartStat.NeedsAction -> "needs action"
    }
    override fun inviteReplyProcessed(attendee: String, partStat: IcsPartStat) =
        "$attendee ${partStatName(partStat)} the invitation"
    override fun inviteReplyUnknown(attendee: String) = "$attendee's reply matched no local event"
    override val language = "Language"
    override val languageSystem = "System"
    override val languageChinese = "中文"
    override val languageEnglish = "English"
    override val appearance = "Appearance"
    override val modeSystem = "System"
    override val modeLight = "Light"
    override val modeDark = "Dark"
    override val colorPalette = "Palette"
    override val paletteMonet = "Monet"
    override val paletteMonochrome = "Monochrome"
    override val paletteCustom = "Custom"
    override val defaultHourNotSet = "Not set"
    override val timelineStart = "Default start time"
    override val startHour = "Start hour"
    override val about = "About"
    override val version = "Version"
    override val storageNote = "Data is stored on this device"
}

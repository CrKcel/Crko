package com.crko.i18n

import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsWarning
import com.crko.domain.SubscriptionFailure
import com.crko.domain.TodoPriority
import com.crko.domain.DiscoveryFailure
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.number

enum class AppLanguage { System, Chinese, English }

/** Locales other than Chinese fall back to English. */
fun systemLanguage(tag: String): AppLanguage =
    if (tag.lowercase().startsWith("zh")) AppLanguage.Chinese else AppLanguage.English

fun resolveLanguage(setting: AppLanguage, systemTag: String): AppLanguage =
    if (setting == AppLanguage.System) systemLanguage(systemTag) else setting

fun languageTag(language: AppLanguage): String = when (language) {
    AppLanguage.Chinese -> "zh"
    AppLanguage.English -> "en"
    AppLanguage.System -> ""
}

fun languageFromTag(tag: String): AppLanguage = when (tag) {
    "zh" -> AppLanguage.Chinese
    "en" -> AppLanguage.English
    else -> AppLanguage.System
}

interface Strings {
    val appName: String
    val tabSchedule: String
    val tabMore: String

    val today: String
    val tomorrow: String
    val yesterday: String
    val weekdaysFull: List<String>
    val weekdaysShort: List<String>

    fun monthTitle(year: Int, month: Month): String
    fun dayMonth(date: LocalDate): String
    fun monthName(month: Month): String
    fun time(time: LocalTime): String
    fun timeRange(start: LocalTime, end: LocalTime): String
    fun relativeDay(date: LocalDate, delta: Int): String

    val eventNew: String
    val eventEdit: String
    val eventDetail: String
    val eventTitleField: String
    val noteField: String
    val save: String
    val cancel: String
    val edit: String
    val close: String
    val done: String
    val saved: String
    val deleted: String
    /** Snackbar action that restores the most recent delete. */
    val undo: String
    val deleteUndone: String
    val deleteUndoFailed: String
    val todoAdded: String
    val delete: String
    val startTime: String
    val endTime: String
    val invalidTimeRange: String
    val emptyTitle: String
    val noEventsToday: String
    val noEventsSelected: String
    val expandCalendar: String
    val collapseCalendar: String
    val scheduleHint: String
    fun eventsCount(count: Int): String
    val switchView: String
    val viewTimeline: String
    val viewList: String
    val dayView: String
    val weekOverview: String
    val repeat: String
    val repeatNone: String
    val repeatDaily: String
    val repeatWeekly: String
    val repeatMonthly: String
    val repeatYearly: String
    val repeatCustom: String
    val repeatWeekdaysRequired: String
    val repeatInterval: String
    val repeatEnd: String
    val repeatNeverEnds: String
    val repeatAfterTimes: String
    val repeatUntilDate: String

    val allDay: String
    val endDate: String
    val location: String
    val locationField: String
    val categories: String
    val categoriesField: String
    val urlField: String
    val attachments: String
    val attachmentSaveFailed: String
    val attachmentBroken: String
    val moreOptions: String
    val eventStatus: String
    val statusConfirmed: String
    val statusTentative: String
    val statusCancelled: String
    val transparency: String
    val busy: String
    val free: String
    val priorityNone: String
    val reminder: String
    val reminderNone: String
    val reminderAtTime: String
    fun reminderBefore(minutes: Int): String
    fun reminderAfterStart(minutes: Int): String
    val reminderAtEnd: String
    fun reminderBeforeEnd(minutes: Int): String
    fun reminderAfterEnd(minutes: Int): String
    val reminderRelativeTo: String
    val reminderRelativeToStart: String
    val reminderRelativeToEnd: String
    val editScope: String
    val scopeThisEvent: String
    val scopeThisAndFuture: String
    val scopeAllEvents: String

    val todos: String
    val todoNew: String
    val todoEdit: String
    val todoDetail: String
    val todoTitleField: String
    val untitledTodo: String
    val advancedSettings: String
    val startDate: String
    val progress: String
    val priority: String
    val priorityHigh: String
    val priorityNormal: String
    val priorityLow: String
    val dueDate: String
    val clear: String
    val customDate: String
    val selectDate: String
    val incomplete: String
    val completed: String
    val todoStatusInProgress: String
    val todoStatusCancelled: String
    val emptyTodos: String

    /** Completion scope of a repeating todo. */
    val todoActions: String
    val todoCompleteSeries: String
    val todoStopRecurrence: String

    fun items(count: Int): String

    /** `RELATED-TO` sub-tasks. */
    val subtaskAdd: String
    fun subtaskOf(parent: String): String

    /** `VJOURNAL` browsing and editing. */
    val journals: String
    val journalNew: String
    val journalEdit: String
    val journalEmpty: String
    val journalDate: String

    /** Global search across events, todos and journals. */
    val search: String
    val searchHint: String
    val searchNoResults: String
    val searchFilters: String
    val searchAllCalendars: String
    val searchAllCategories: String
    val searchDateFrom: String
    val searchDateTo: String
    val searchRecurring: String
    val searchLimitReached: String
    val searchTypeEvents: String
    val searchTypeTodos: String
    val searchTypeJournals: String

    val statistics: String
    val settings: String
    val calendarsAndData: String
    val back: String
    val completedToday: String
    val activeTodos: String
    val upcomingEvents: String
    val last7Days: String
    val byPriority: String
    val thisWeek: String
    val todayEvents: String

    val calendarData: String
    val importIcs: String
    val importIcsHint: String
    val exportIcs: String
    val exportIcsHint: String
    val nothingToImport: String
    val importFailed: String
    val exportFailed: String
    val exported: String
    fun importedItems(events: Int, todos: Int, journals: Int, removed: Int): String
    fun importFailure(failure: IcsImportFailure): String
    fun importWarning(warning: IcsWarning): String
    fun importWarningLocation(line: Int?, record: String?): String
    fun importWarningMore(count: Int): String
    fun storageRecovered(droppedRecords: Int): String
    val untitledEvent: String

    /** Per-record import preview. */
    val importPreviewTitle: String
    val importPreviewEvent: String
    val importPreviewTodo: String
    val importPreviewJournal: String
    val importPreviewRemove: String
    val importPreviewEmpty: String
    val importSelectAll: String
    val importSelectNone: String
    val importConfirm: String

    /** Calendar subscriptions (webcal/ICS feeds and CalDAV collections). */
    val subscriptions: String
    val subscriptionsHint: String
    val subscriptionAdd: String
    val subscriptionAddHint: String
    val subscriptionName: String
    val subscriptionUrl: String
    val subscriptionUrlInvalid: String
    val subscriptionSyncNow: String
    val subscriptionNeverSynced: String
    val subscriptionsEmpty: String
    fun subscriptionLastSynced(at: String): String
    fun subscriptionSynced(name: String, events: Int, todos: Int, removed: Int): String
    fun subscriptionUnchanged(name: String): String
    fun subscriptionFailed(name: String, failure: SubscriptionFailure): String

    /** CalDAV accounts: discovery-based sign-in and two-way sync. */
    val caldavAdd: String
    val caldavAddHint: String
    val caldavServer: String
    val caldavUsername: String
    val caldavPassword: String
    val caldavDiscover: String
    val caldavDiscovering: String
    val caldavNoCalendars: String
    val caldavWritable: String
    val caldavReadonly: String
    val caldavSave: String
    val caldavEditCredentials: String
    val caldavCredentialsSaved: String
    val caldavRemoveSummary: String
    fun caldavDiscoverFailed(failure: DiscoveryFailure): String

    /** The crko.cloud account: end-to-end encrypted sync of the local calendars. */
    val cloudSync: String
    val cloudNotSignedIn: String
    val cloudSyncEnabled: String
    val cloudSyncNow: String
    val cloudSyncing: String
    val cloudNeverSynced: String
    fun cloudLastSyncAt(at: String): String
    val cloudE2eeNote: String
    val cloudSignIn: String
    val cloudSignUp: String
    val cloudSwitchToSignIn: String
    val cloudSwitchToSignUp: String
    val cloudServerField: String
    val cloudInviteField: String
    val cloudNameField: String
    val cloudPasswordField: String
    val cloudWorking: String
    val cloudSignOut: String
    val cloudSignOutSummary: String
    val cloudDeleteAccount: String
    val cloudDeleteAccountSummary: String
    val cloudErrorInvite: String
    val cloudErrorNameTaken: String
    val cloudErrorCredentials: String
    val cloudErrorUnknownAccount: String
    val cloudErrorServer: String
    val cloudErrorGeneric: String
    fun cloudSynced(events: Int, todos: Int, journals: Int, removed: Int): String
    val cloudUnchanged: String
    val cloudAuthExpired: String

    /** Local calendar management. */
    val calendars: String
    val calendar: String
    val calendarAdd: String
    val calendarAddHint: String
    val calendarName: String
    val calendarColor: String
    val calendarDefault: String
    val calendarSetDefault: String
    val calendarHidden: String
    val calendarExport: String
    val calendarRename: String
    val calendarDelete: String
    fun calendarDeleteSummary(name: String, records: Int): String

    /** Name of a `PARTSTAT` for invitation feedback. */
    fun partStatName(partStat: IcsPartStat): String
    fun inviteReplyProcessed(attendee: String, partStat: IcsPartStat): String
    fun inviteReplyUnknown(attendee: String): String

    val language: String
    val languageSystem: String
    val languageChinese: String
    val languageEnglish: String
    val appearance: String
    val modeSystem: String
    val modeLight: String
    val modeDark: String
    val colorPalette: String
    val paletteMonet: String
    val paletteMonochrome: String
    val paletteCustom: String
    val defaultHourNotSet: String
    val timelineStart: String
    val startHour: String

    val gestures: String
    val gesturePinchOverview: String
    val gestureSwipeSwitch: String

    val about: String
    val version: String
    val storageNote: String

    // ----- today, focus and app prompts -----

    val tabToday: String
    val dayPlan: String
    val dayPlanHint: String
    val dayPlanEmpty: String
    val dayPlanPick: String
    val dayPlanClear: String
    val energy: String
    val energyLow: String
    val energyMedium: String
    val energyHigh: String
    val nextStep: String
    val firstStepLabel: String
    val startFiveMinutes: String
    val cantMove: String
    val cantMoveTitle: String
    val cantMoveSmaller: String
    val cantMoveSmallerHint: String
    val cantMoveTwoMinutes: String
    val cantMoveTwoMinutesHint: String
    val cantMoveOther: String
    val cantMoveOtherHint: String
    val cantMoveStop: String
    val smallerStepTitle: String
    val smallerStepHint: String
    val smallerStepCustom: String
    val smallerStepUse: String
    val smallerStepIdeas: List<String>
    val otherTaskTitle: String
    val otherTaskEmpty: String
    val stoppedForToday: String
    val stoppedForTodayHint: String
    val continueDay: String
    val quickCaptureHint: String
    val quickCaptureAdd: String
    val completedTodaySection: String
    val focus: String
    val focusRunning: String
    val focusPause: String
    val focusResume: String
    val focusEnd: String
    val focusTimeUp: String
    val focusContinue: String
    val focusAskComplete: String
    val focusComplete: String
    val focusNotYet: String
    val focusCollapse: String
    val focusExpand: String
    val todoPool: String
    val remindersSection: String
    val appPromptBudget: String
    val appPromptBudgetHint: String
    val budgetOff: String
    val budgetThree: String
    val budgetSix: String
    val reminderActionOpen: String
    val reminderActionSnooze: String
    val reminderActionStart: String
}

fun todoPriorityLabel(strings: Strings, priority: TodoPriority): String = when (priority) {
    TodoPriority.High -> strings.priorityHigh
    TodoPriority.Normal -> strings.priorityNormal
    TodoPriority.Low -> strings.priorityLow
}

/**
 * Human label for a relative alarm trigger. `minutes` is positive for "before" (the editor's
 * presets), negative for "after" (only reachable from imported `VALARM`s), and [atEnd] selects the
 * `RELATED=END` wording.
 */
fun Strings.alarmLabel(minutes: Int, atEnd: Boolean): String = when {
    minutes == 0 -> if (atEnd) reminderAtEnd else reminderAtTime
    minutes > 0 -> if (atEnd) reminderBeforeEnd(minutes) else reminderBefore(minutes)
    else -> if (atEnd) reminderAfterEnd(-minutes) else reminderAfterStart(-minutes)
}

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.Chinese -> ZhStrings
    else -> EnStrings
}


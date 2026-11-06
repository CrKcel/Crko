package com.crko.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.crko.domain.occurrenceStartOn
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.state.entry.EntryInbox
import com.crko.state.entry.EntryTarget
import com.crko.state.today.FocusController
import com.crko.state.today.FocusEnd
import com.crko.state.today.TodayController
import com.crko.ui.components.AddFab
import com.crko.ui.components.GlassNavigationBar
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.components.GlassNavigationBarItem
import com.crko.ui.more.CalendarSheet
import com.crko.ui.more.MoreTab
import com.crko.ui.more.SettingsPage
import com.crko.ui.schedule.EventDetailDialog
import com.crko.ui.schedule.EventEditorDialog
import com.crko.ui.schedule.ScheduleTab
import com.crko.ui.search.SearchHost
import com.crko.ui.today.FocusDoneDialog
import com.crko.ui.today.FocusScreen
import com.crko.ui.today.TodayTab
import com.crko.ui.today.completeTodo
import com.crko.ui.today.isLocalTodo
import com.crko.ui.today.rememberNowMillis
import com.crko.ui.todo.TodoEditorDialog
import com.crko.ui.transfer.ImportPreview
import com.crko.ui.transfer.ImportPreviewDialog
import com.crko.ui.transfer.rememberCalendarFileBridge
import com.crko.ui.transfer.rememberImportCoordinator
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import com.crko.ui.icons.AppIcons

/**
 * The app shell: tab switching, the three pages, and the display state of the editor, the calendar
 * bottom sheet, the settings page and the import preview. File flows live in
 * [com.crko.ui.transfer.ImportCoordinator]; message text and the snackbar queue live in
 * [rememberMessagePresenter].
 */
@Composable
internal fun CrkoApp(
    state: AppStateHolder,
    todayController: TodayController,
    focusController: FocusController,
) {
    val strings = state.strings
    // Three slots: schedule on the left, the default today page in the middle, more on the right.
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var eventEditor by remember { mutableStateOf<EventEditorTarget?>(null) }
    var eventDetail by remember { mutableStateOf<EventEditorTarget?>(null) }
    var showTodoEditor by remember { mutableStateOf(false) }
    var showCalendarSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var captureRequest by rememberSaveable { mutableIntStateOf(0) }
    var focusExpanded by remember { mutableStateOf(focusController.isRunning) }
    var focusEnd by remember { mutableStateOf<FocusEnd?>(null) }
    // The pages behind the launch destination are kept composed for smooth switching, but building
    // all of them on the first frame delays the content itself. They join one frame later, and a
    // tab picked before that composes immediately.
    var secondaryTabsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        secondaryTabsReady = true
    }

    val now = rememberClock(state)
    val keyboard = LocalSoftwareKeyboardController.current
    val fileBridge = rememberCalendarFileBridge()
    val snackbar = rememberMessagePresenter(state)
    val importer = rememberImportCoordinator(state, fileBridge) { importPreview = it }

    // The content layer the glass bar refracts. It is recorded while the tab content draws, and
    // read by the bar, which is drawn afterwards in the same Box.
    val backdrop = rememberLayerBackdrop()
    val layoutDirection = LocalLayoutDirection.current
    val barItems = remember(strings) {
        listOf(
            GlassNavigationBarItem(AppIcons.Timeline, strings.tabSchedule),
            GlassNavigationBarItem(AppIcons.Today, strings.tabToday),
            GlassNavigationBarItem(AppIcons.Tune, strings.tabMore),
        )
    }

    fun finishFocus() {
        val ended = focusController.finish()
        if (ended.taskId != null && ended.actualMinutes > 0) {
            state.recordTodoActualMinutes(ended.taskId, ended.actualMinutes)
        }
        focusEnd = ended
        focusExpanded = false
        tab = 0
    }

    // Entries from deep links and notification actions wait in the inbox until the restored state
    // is ready; consuming them here keeps a cold start on the same landing page as a warm one.
    LaunchedEffect(state) {
        EntryInbox.entries.collect { target ->
            when (target) {
                EntryTarget.Today -> {
                    tab = 1
                    focusExpanded = false
                }

                EntryTarget.Capture -> {
                    tab = 1
                    // Capture is an explicit request for the input field; it takes precedence over
                    // a running focus screen, which stays available through its banner.
                    focusExpanded = false
                    captureRequest++
                }

                is EntryTarget.Focus -> {
                    tab = 1
                    if (focusController.session == null) {
                        focusController.start(target.taskId ?: todayController.nextTask?.id, 5)
                    }
                    focusExpanded = true
                }
            }
        }
    }

    LaunchedEffect(focusController.session?.startedAtMs) {
        if (focusController.session == null) focusExpanded = false
    }

    LaunchedEffect(state.today) {
        todayController.refreshDay()
    }

    // Opening the focus screen takes over the page; the capture field's keyboard must not cover
    // its pause and end controls.
    LaunchedEffect(focusExpanded) {
        if (focusExpanded) keyboard?.hide()
    }

    Scaffold(
        // The glass bar floats over the content instead of occupying the bottomBar slot: the tabs
        // run behind it so the blur and refraction have something to pick up.
        snackbarHost = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = GlassNavigationBarDefaults.clearance()),
            ) {
                SnackbarHost(state = snackbar)
            }
        },
    ) { padding ->
        // Tab content runs to the window bottom so nothing ends in a strip above the glass bar;
        // the bar itself keeps its own inset-based margin.
        val tabPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDirection),
            bottom = 0.dp,
        )
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                if (tab == 0 || secondaryTabsReady) {
                    TabPage(visible = tab == 0) {
                        ScheduleTab(
                            state = state,
                            now = now,
                            strings = strings,
                            contentPadding = tabPadding,
                            onOpenEvent = { event, date ->
                                // Only a repeating series needs the occurrence slot; a one-off event
                                // keeps its own start date even when opened from a later day.
                                val occurrence = if (event.repeat != null) event.occurrenceStartOn(date) else null
                                eventDetail = EventEditorTarget(event, occurrence)
                            },
                            onOpenSearch = { showSearch = true },
                        )
                    }
                }
                if (tab == 1 || secondaryTabsReady) {
                    TabPage(visible = tab == 1) {
                        TodayTab(
                            state = state,
                            controller = todayController,
                            focus = focusController,
                            strings = strings,
                            contentPadding = tabPadding,
                            captureRequest = captureRequest,
                            focusVisible = focusExpanded,
                            onStartFocus = { taskId, minutes ->
                                focusController.start(taskId, minutes)
                                focusExpanded = true
                                tab = 1
                            },
                            onOpenFocus = { focusExpanded = true },
                            onOpenSearch = { showSearch = true },
                        )
                    }
                }
                if (tab == 2 || secondaryTabsReady) {
                    TabPage(visible = tab == 2) {
                        MoreTab(
                            state = state,
                            strings = strings,
                            contentPadding = tabPadding,
                            onOpenCalendarSheet = { showCalendarSheet = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }

            AddFab(
                onClick = {
                    when (tab) {
                        0 -> eventEditor = EventEditorTarget(newEvent(state, now.value), null)
                        1 -> showTodoEditor = true
                        else -> showSettings = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = GlassNavigationBarDefaults.HorizontalMargin,
                        bottom = GlassNavigationBarDefaults.clearance() + 12.dp,
                    ),
            )

            GlassNavigationBar(
                backdrop = backdrop,
                items = barItems,
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            val focusSession = focusController.session
            if (focusExpanded && focusSession != null) {
                val nowMillis = rememberNowMillis()
                FocusScreen(
                    task = state.appState.visibleTodos.firstOrNull { it.id == focusSession.taskId },
                    firstStep = state.appState.visibleTodos
                        .firstOrNull { it.id == focusSession.taskId }
                        ?.let { todo -> if (isLocalTodo(state, todo)) todo.coaching?.firstStep.orEmpty() else "" }
                        .orEmpty(),
                    strings = strings,
                    remainingMillis = focusController.remainingMillis(nowMillis.value),
                    paused = focusController.isPaused,
                    finished = focusController.isFinished(nowMillis.value),
                    onPause = focusController::pause,
                    onResume = focusController::resume,
                    onEnd = { finishFocus() },
                    onContinue = { focusController.extend(5) },
                    onCollapse = { focusExpanded = false },
                )
            }
        }
    }

    eventDetail?.let { target ->
        EventDetailDialog(
            event = target.event,
            occurrenceDate = target.occurrenceDate,
            calendar = state.appState.calendars.firstOrNull { calendar ->
                calendar.events.any { it.id == target.event.id }
            },
            strings = strings,
            onDismiss = { eventDetail = null },
            onEdit = {
                eventDetail = null
                eventEditor = target
            },
        )
    }

    EventEditorDialog(
        event = eventEditor?.event,
        occurrenceDate = eventEditor?.occurrenceDate,
        strings = strings,
        today = state.today,
        readAttachment = state::attachmentBytes,
        calendars = state.editorCalendars,
        defaultCalendarId = state.newRecordCalendarId,
        onDismiss = { eventEditor = null },
        onSave = { event, scope, calendarId ->
            val saved = state.saveEvent(
                event,
                occurrenceDate = eventEditor?.occurrenceDate,
                scope = scope,
                calendarId = calendarId,
            ) == WriteResult.Success
            if (saved) state.notify(AppMessage.Saved)
            saved
        },
        onDelete = { event, scope ->
            // The state layer announces the delete itself and offers the undo action.
            state.deleteEvent(
                event,
                occurrenceDate = eventEditor?.occurrenceDate,
                scope = scope,
            )
        },
    )

    TodoEditorDialog(
        show = showTodoEditor,
        strings = strings,
        today = state.today,
        onDismiss = { showTodoEditor = false },
        onSave = { draft, calendarId ->
            val result = state.addTodo(draft, calendarId = calendarId)
            if (result == WriteResult.Success) state.notify(AppMessage.TodoAdded)
            result == WriteResult.Success
        },
        calendars = state.editorCalendars,
        defaultCalendarId = state.newRecordCalendarId,
    )

    CalendarSheet(
        show = showCalendarSheet,
        state = state,
        strings = strings,
        onDismiss = { showCalendarSheet = false },
        onImportCalendar = importer::importCalendar,
        onExportCalendar = importer::exportCalendar,
    )

    SettingsPage(
        show = showSettings,
        state = state,
        strings = strings,
        onBack = { showSettings = false },
    )

    // Search over both list pages; tapped todos show a detail card inside the host, an event goes
    // through the same detail target the schedule page uses, and journals open their editor.
    SearchHost(
        open = showSearch,
        state = state,
        strings = strings,
        onDismiss = { showSearch = false },
        onOpenEvent = { event -> eventDetail = EventEditorTarget(event, null) },
    )

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            strings = strings,
            onConfirm = { checked ->
                importPreview = null
                importer.apply(preview, checked)
            },
            onDismiss = { importPreview = null },
        )
    }

    // Ending a focus session asks about the task, never completes it on its own.
    focusEnd?.let { ended ->
        state.appState.todos.firstOrNull { it.id == ended.taskId }?.let { todo ->
            FocusDoneDialog(
                task = todo,
                strings = strings,
                onComplete = {
                    completeTodo(state, todo, state.nextTodoOccurrence(todo), state.today)
                    focusEnd = null
                },
                onNotYet = { focusEnd = null },
            )
        }
    }
}

package com.crko.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.crko.domain.Event
import com.crko.domain.IcsImportFailure
import com.crko.domain.LocalClock
import com.crko.domain.MaxEndMinutes
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
import com.crko.domain.occurrenceStartOn
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.ui.more.MoreTab
import com.crko.ui.more.SettingsSheet
import com.crko.ui.schedule.EventEditorDialog
import com.crko.ui.schedule.ScheduleTab
import com.crko.ui.todo.TodoEditorDialog
import com.crko.ui.todo.TodoTab
import com.crko.ui.transfer.rememberCalendarFileBridge
import com.crko.ui.components.AddFab
import com.crko.ui.components.GlassNavigationBar
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.components.GlassNavigationBarItem
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import kotlin.time.Clock
import com.crko.ui.icons.AppIcons


private data class EventEditorTarget(val event: Event, val occurrenceDate: LocalDate?)

@Composable
fun CrkoApp(state: AppStateHolder) {
    val strings = state.strings
    // The schedule is the primary surface, so the middle tab is the launch destination.
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var eventEditor by remember { mutableStateOf<EventEditorTarget?>(null) }
    var showTodoEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val now = rememberClock(state)
    val scope = rememberCoroutineScope()
    val fileBridge = rememberCalendarFileBridge()
    val snackbar = remember { SnackbarHostState() }
    // The collector outlives recompositions; read the latest strings so messages follow a
    // language switch made after the screen was first shown.
    val currentStrings by rememberUpdatedState(strings)

    LaunchedEffect(Unit) {
        state.messages.collectLatest { message ->
            val text = when (message) {
                AppMessage.Saved -> currentStrings.saved
                AppMessage.Deleted -> currentStrings.deleted
                AppMessage.TodoAdded -> currentStrings.todoAdded
                is AppMessage.EventsImported -> {
                    if (message.events == 0 && message.todos == 0) {
                        currentStrings.nothingToImport
                    } else {
                        val summary = currentStrings.importedItems(message.events, message.todos)
                        val warnings = message.warnings.distinct().map(currentStrings::importWarning)
                        (listOf(summary) + warnings).joinToString("; ")
                    }
                }
                is AppMessage.ImportFailed -> currentStrings.importFailure(message.failure)
                AppMessage.Exported -> currentStrings.exported
                AppMessage.ExportFailed -> currentStrings.exportFailed
                is AppMessage.StorageRecovered -> currentStrings.storageRecovered(message.droppedRecords)
            }
            snackbar.showSnackbar(text)
        }
    }

    fun importCalendar() {
        val untitled = state.strings.untitledEvent
        fileBridge.openImport(
            onPicked = { text ->
                scope.launch {
                    val imported = runCatching {
                        withContext(Dispatchers.Default) {
                            importCalendar(text, TimeZone.currentSystemDefault(), untitled)
                        }
                    }.getOrNull()
                    if (imported == null) {
                        state.notify(AppMessage.ImportFailed(IcsImportFailure.InvalidFormat))
                    } else {
                        val (events, todos) = state.importCalendar(imported)
                        state.notify(AppMessage.EventsImported(events, todos, imported.warnings))
                    }
                }
            },
            onError = { failure -> state.notify(AppMessage.ImportFailed(failure)) },
        )
    }

    fun exportCalendar() {
        val events = state.appState.events
        val todos = state.appState.todos
        val fileName = "crko-${state.today}.ics"
        scope.launch {
            val content = withContext(Dispatchers.Default) {
                exportCalendar(events, todos, calendarName = "Crko")
            }
            fileBridge.saveExport(
                fileName = fileName,
                content = content,
                onExported = { state.notify(AppMessage.Exported) },
                onError = { state.notify(AppMessage.ExportFailed) },
            )
        }
    }

    // The content layer the glass bar refracts. It is recorded while the tab content draws, and
    // read by the bar, which is drawn afterwards in the same Box.
    val backdrop = rememberLayerBackdrop()
    val layoutDirection = LocalLayoutDirection.current
    val barItems = remember(strings) {
        listOf(
            GlassNavigationBarItem(AppIcons.Task, strings.tabTodo),
            GlassNavigationBarItem(AppIcons.Timeline, strings.tabSchedule),
            GlassNavigationBarItem(AppIcons.Tune, strings.tabMore),
        )
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
                TabPage(visible = tab == 0) {
                    TodoTab(
                        state = state,
                        strings = strings,
                        contentPadding = tabPadding,
                    )
                }
                TabPage(visible = tab == 1) {
                    ScheduleTab(
                        state = state,
                        now = now,
                        strings = strings,
                        contentPadding = tabPadding,
                        onOpenEvent = { event, date ->
                            // Only a repeating series needs the occurrence slot; a one-off event
                            // keeps its own start date even when opened from a later day.
                            val occurrence = if (event.repeat != null) event.occurrenceStartOn(date) else null
                            eventEditor = EventEditorTarget(event, occurrence)
                        },
                    )
                }
                TabPage(visible = tab == 2) {
                    MoreTab(
                        state = state,
                        strings = strings,
                        contentPadding = tabPadding,
                        onOpenSettings = { showSettings = true },
                        onImportCalendar = ::importCalendar,
                        onExportCalendar = ::exportCalendar,
                    )
                }
            }

            AddFab(
                onClick = {
                    when (tab) {
                        0 -> showTodoEditor = true
                        1 -> eventEditor = EventEditorTarget(newEvent(state, now.value), null)
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
        }
    }

    EventEditorDialog(
        event = eventEditor?.event,
        occurrenceDate = eventEditor?.occurrenceDate,
        strings = strings,
        today = state.today,
        onDismiss = { eventEditor = null },
        onSave = { event, scope ->
            val saved = state.saveEvent(
                event,
                occurrenceDate = eventEditor?.occurrenceDate,
                scope = scope,
            ) == WriteResult.Success
            if (saved) state.notify(AppMessage.Saved)
            saved
        },
        onDelete = { event, scope ->
            state.deleteEvent(
                event,
                occurrenceDate = eventEditor?.occurrenceDate,
                scope = scope,
            )
            state.notify(AppMessage.Deleted)
        },
    )

    TodoEditorDialog(
        show = showTodoEditor,
        strings = strings,
        today = state.today,
        onDismiss = { showTodoEditor = false },
        onSave = { title, priority, due ->
            val result = state.addTodo(title, priority, due)
            if (result == WriteResult.Success) state.notify(AppMessage.TodoAdded)
            result == WriteResult.Success
        },
    )

    SettingsSheet(
        show = showSettings,
        state = state,
        strings = strings,
        onDismiss = { showSettings = false },
    )
}

/**
 * One tab's page, kept composed while another tab is shown. Building a page costs tens of
 * milliseconds (the calendar and timeline lay out a lot), and composing it on the frame the bar's
 * selection bubble is released made that animation skip frames. Pages that are not on top are
 * skipped while drawing, so they stay out of the bar's backdrop, and they swallow touches so a tap
 * the visible page ignores cannot reach the page underneath.
 */
@Composable
private fun TabPage(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .drawWithContent {
                if (visible) drawContent()
            }
            .then(
                if (visible) {
                    Modifier
                } else {
                    // Invisible to screen readers too; otherwise they would walk into a page the
                    // user cannot see.
                    Modifier
                        .clearAndSetSemantics { }
                        .swallowTouches()
                },
            ),
    ) {
        content()
    }
}

private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private fun newEvent(state: AppStateHolder, now: LocalClock): Event {
    val start = ((now.time.hour * 60 + now.time.minute) / 30 + 1) * 30
    val startMinutes = start.coerceAtMost(23 * 60 + 30)
    return Event(
        id = null,
        title = "",
        date = state.today.toString(),
        startMinutes = startMinutes,
        endMinutes = (startMinutes + 60).coerceAtMost(MaxEndMinutes),
    )
}

/**
 * Ticks once a minute, on the minute boundary, and rolls the day over at midnight.
 *
 * The clock is exposed as a [State] rather than a value: the screens only read it where the time is
 * actually drawn or a tap needs it, so a tick repaints the time marker without recomposing the
 * schedule, its calendar or its timelines.
 */
@Composable
private fun rememberClock(state: AppStateHolder): State<LocalClock> {
    val clock = remember { mutableStateOf(localClockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            val instant = Clock.System.now()
            delay((60 - (instant.epochSeconds % 60)).seconds)
            clock.value = localClockNow()
            state.refreshToday()
        }
    }
    return clock
}

private fun localClockNow(): LocalClock {
    val instant = Clock.System.now()
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return LocalClock(instant, local.date, local.time)
}

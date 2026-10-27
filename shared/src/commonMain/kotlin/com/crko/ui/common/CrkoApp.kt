package com.crko.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.crko.domain.Event
import com.crko.domain.LocalClock
import com.crko.domain.MaxEndMinutes
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.ui.more.MoreTab
import com.crko.ui.more.SettingsSheet
import com.crko.ui.schedule.EventEditorDialog
import com.crko.ui.schedule.ScheduleTab
import com.crko.ui.todo.TodoEditorDialog
import com.crko.ui.todo.TodoTab
import com.crko.ui.components.AddFab
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import kotlin.time.Clock
import com.crko.ui.icons.AppIcons


@Composable
fun CrkoApp(state: AppStateHolder) {
    val strings = state.strings
    // The schedule is the primary surface, so the middle tab is the launch destination.
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var eventEditor by remember { mutableStateOf<Event?>(null) }
    var showTodoEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val now = rememberClock(state)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        state.messages.collectLatest { message ->
            val text = when (message) {
                AppMessage.Saved -> strings.saved
                AppMessage.Deleted -> strings.deleted
                AppMessage.TodoAdded -> strings.todoAdded
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        // The scaffold fills the whole window: content receives status-bar and display-cutout
        // insets as PaddingValues, and the NavigationBar is docked at the true window bottom so
        // its background extends behind the gesture indicator while its items stay clear of it.
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = AppIcons.Task,
                    label = strings.tabTodo,
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = AppIcons.Timeline,
                    label = strings.tabSchedule,
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = AppIcons.Tune,
                    label = strings.tabMore,
                )
            }
        },
        floatingActionButton = {
            AddFab(
                onClick = {
                    when (tab) {
                        0 -> showTodoEditor = true
                        1 -> eventEditor = newEvent(state, now)
                        else -> showSettings = true
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbar) },
    ) { padding ->
        Crossfade(targetState = tab, animationSpec = tween(180), modifier = Modifier.fillMaxSize()) { current ->
            when (current) {
                0 -> TodoTab(
                    state = state,
                    now = now,
                    strings = strings,
                    contentPadding = padding,
                    onAddTodo = { showTodoEditor = true },
                )
                1 -> ScheduleTab(
                    state = state,
                    now = now,
                    strings = strings,
                    contentPadding = padding,
                    onOpenEvent = { eventEditor = it },
                )
                else -> MoreTab(
                    state = state,
                    now = now,
                    strings = strings,
                    contentPadding = padding,
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }

    EventEditorDialog(
        event = eventEditor,
        strings = strings,
        onDismiss = { eventEditor = null },
        onSave = { event ->
            when (state.saveEvent(event)) {
                WriteResult.Success -> true
                else -> false
            }
        },
        onDelete = { state.deleteEvent(it.id) },
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

/** Ticks once a minute, on the minute boundary, and rolls the day over at midnight. */
@Composable
private fun rememberClock(state: AppStateHolder): LocalClock {
    var clock by remember { mutableStateOf(localClockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            val instant = Clock.System.now()
            delay(1000L * (60 - (instant.epochSeconds % 60)))
            clock = localClockNow()
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

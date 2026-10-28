package com.crko.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
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
import com.crko.ui.components.GlassNavigationBar
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.components.GlassNavigationBarItem
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
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
    // The collector outlives recompositions; read the latest strings so messages follow a
    // language switch made after the screen was first shown.
    val currentStrings by rememberUpdatedState(strings)

    LaunchedEffect(Unit) {
        state.messages.collectLatest { message ->
            val text = when (message) {
                AppMessage.Saved -> currentStrings.saved
                AppMessage.Deleted -> currentStrings.deleted
                AppMessage.TodoAdded -> currentStrings.todoAdded
            }
            snackbar.showSnackbar(text)
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
            Crossfade(
                targetState = tab,
                animationSpec = tween(180),
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) { current ->
                when (current) {
                    0 -> TodoTab(
                        state = state,
                        strings = strings,
                        contentPadding = tabPadding,
                    )
                    1 -> ScheduleTab(
                        state = state,
                        now = now,
                        strings = strings,
                        contentPadding = tabPadding,
                        onOpenEvent = { eventEditor = it },
                    )
                    else -> MoreTab(
                        state = state,
                        strings = strings,
                        contentPadding = tabPadding,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }

            AddFab(
                onClick = {
                    when (tab) {
                        0 -> showTodoEditor = true
                        1 -> eventEditor = newEvent(state, now)
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
        event = eventEditor,
        strings = strings,
        onDismiss = { eventEditor = null },
        onSave = { event ->
            val saved = state.saveEvent(event) == WriteResult.Success
            if (saved) state.notify(AppMessage.Saved)
            saved
        },
        onDelete = {
            state.deleteEvent(it.id)
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
            delay((60 - (instant.epochSeconds % 60)).seconds)
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

package com.crko.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.crko.domain.occurrenceStartOn
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.ui.components.AddFab
import com.crko.ui.components.GlassNavigationBar
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.components.GlassNavigationBarItem
import com.crko.ui.more.MoreTab
import com.crko.ui.more.SettingsSheet
import com.crko.ui.schedule.EventEditorDialog
import com.crko.ui.schedule.ScheduleTab
import com.crko.ui.todo.TodoEditorDialog
import com.crko.ui.todo.TodoTab
import com.crko.ui.transfer.ImportPreview
import com.crko.ui.transfer.ImportPreviewDialog
import com.crko.ui.transfer.rememberCalendarFileBridge
import com.crko.ui.transfer.rememberImportCoordinator
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import com.crko.ui.icons.AppIcons

/**
 * The app shell: tab switching, the three pages, and the display state of the editor, settings and
 * import preview. File flows live in [com.crko.ui.transfer.ImportCoordinator]; message text and the
 * snackbar queue live in [rememberMessagePresenter].
 */
@Composable
fun CrkoApp(state: AppStateHolder) {
    val strings = state.strings
    // The schedule is the primary surface, so the middle tab is the launch destination.
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var eventEditor by remember { mutableStateOf<EventEditorTarget?>(null) }
    var showTodoEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }

    val now = rememberClock(state)
    val fileBridge = rememberCalendarFileBridge()
    val snackbar = rememberMessagePresenter(state)
    val importer = rememberImportCoordinator(state, fileBridge) { importPreview = it }

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
                        onImportCalendar = importer::importCalendar,
                        onExportCalendar = importer::exportCalendar,
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
        readAttachment = state::attachmentBytes,
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
        onSave = { title, priority, due, _ ->
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
}

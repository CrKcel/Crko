package com.crko.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.search.MaxSearchResults
import com.crko.state.search.SearchFilters
import com.crko.state.search.SearchHit
import com.crko.state.search.SearchType
import com.crko.ui.icons.AppIcons
import com.crko.ui.schedule.DatePickerDialog
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import top.yukonga.miuix.kmp.utils.platformDialogProperties

/** Which end of the date window the open picker edits. */
private enum class SearchDateField { From, To }

/**
 * Search sheet: one query field, the filter rows and the grouped results, dropping down from the
 * top of the screen. The query runs through [AppStateHolder.search], i.e. in the state layer; this
 * sheet only renders what comes back and hands the tapped hit to [onOpenHit]. The sheet is rebuilt
 * on every open, so a dismissed search starts clean the next time.
 */
@Composable
internal fun SearchSheet(
    show: Boolean,
    state: AppStateHolder,
    strings: Strings,
    onDismiss: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
) {
    WindowTopSheet(show = show, onDismissRequest = onDismiss) {
        var query by remember { mutableStateOf("") }
        var types by remember { mutableStateOf(SearchType.entries.toSet()) }
        var calendarId by remember { mutableStateOf<String?>(null) }
        var category by remember { mutableStateOf<String?>(null) }
        var from by remember { mutableStateOf<LocalDate?>(null) }
        var to by remember { mutableStateOf<LocalDate?>(null) }
        var picking by remember { mutableStateOf<SearchDateField?>(null) }
        var filtersExpanded by remember { mutableStateOf(false) }

        val filters = SearchFilters(types = types, calendarId = calendarId, category = category, from = from, to = to)
        val hits = state.search(query, filters)
        // The category chips come from the records, so they follow imports and edits of the calendar.
        val categories = remember(state.appState) { state.searchCategories() }

        // Focus the field once the sheet settles so the keyboard is ready for typing; the sheet
        // starts empty, so the IME appearing does not shift the panel itself.
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(300)
            focusRequester.requestFocus()
        }

        // The filter chips stay folded behind the round button; its tint follows the fold state and
        // lights up while any filter is active, so a narrowed search is still readable when folded.
        val activeFilters = listOf(
            types.size < SearchType.entries.size,
            calendarId != null,
            category != null,
            from != null || to != null,
        ).count { it }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = strings.searchHint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (filtersExpanded || activeFilters > 0) {
                            MiuixTheme.colorScheme.primaryContainer
                        } else {
                            MiuixTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { filtersExpanded = !filtersExpanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Expand,
                    contentDescription = strings.searchFilters,
                    tint = if (filtersExpanded || activeFilters > 0) {
                        MiuixTheme.colorScheme.onPrimaryContainer
                    } else {
                        MiuixTheme.colorScheme.onSurfaceContainerHigh
                    },
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (filtersExpanded) 180f else 0f),
                )
            }
        }
        // The filter rows grow out of the field row and shrink back into it, so the sheet itself
        // seems to stretch open and fold closed instead of the chips popping in and out.
        AnimatedVisibility(
            visible = filtersExpanded,
            enter = expandVertically(animationSpec = tween(240, easing = DecelerateEasing(1.5f))) +
                fadeIn(animationSpec = tween(240)),
            exit = shrinkVertically(animationSpec = tween(200, easing = DecelerateEasing(1.5f))) +
                fadeOut(animationSpec = tween(160)),
        ) {
            SearchFiltersBar(
                strings = strings,
                types = types,
                onToggleType = { type -> types = if (type in types) types - type else types + type },
                calendars = state.appState.visibleCalendars,
                calendarId = calendarId,
                onCalendar = { calendarId = it },
                categories = categories,
                category = category,
                onCategory = { category = it },
                from = from,
                to = to,
                onPickFrom = { picking = SearchDateField.From },
                onPickTo = { picking = SearchDateField.To },
                onClearDates = {
                    from = null
                    to = null
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (query.isNotBlank()) {
            when {
                hits.isEmpty() -> SearchHint(strings.searchNoResults)
                else -> {
                    // The list takes whatever room the sheet has left and scrolls there, so long hit
                    // lists stay inside the dropped-down panel instead of growing past the screen.
                    SearchResultsList(
                        hits = hits,
                        strings = strings,
                        onOpen = onOpenHit,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hits.size >= MaxSearchResults) {
                        Text(
                            text = strings.searchLimitReached,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        picking?.let { field ->
            val current = if (field == SearchDateField.From) from else to
            DatePickerDialog(
                initial = current ?: state.today,
                today = state.today,
                strings = strings,
                onDismiss = { picking = null },
                onConfirm = { picked ->
                    if (field == SearchDateField.From) from = picked else to = picked
                    picking = null
                },
            )
        }
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * Window-level sheet that drops down from the top edge, mirroring the miuix bottom sheet: a window
 * dialog host, a dimmed scrim that taps away, and a full-bleed surface with rounded bottom corners
 * sliding in on a spring. The content stays composed through the exit animation and leaves
 * composition when it ends, so state held in [content] resets between opens.
 */
@Composable
private fun WindowTopSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val animationProgress = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    val internalVisible = remember { mutableStateOf(false) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    LaunchedEffect(show) {
        if (show) {
            internalVisible.value = true
            animationProgress.animateTo(1f, folmeSpring(damping = 0.9f, response = 0.38f))
        } else {
            if (!internalVisible.value) return@LaunchedEffect
            if (imeInsets.getBottom(density) > 0) keyboard?.hide()
            animationProgress.animateTo(0f, tween(durationMillis = 260, easing = DecelerateEasing(1.5f)))
            internalVisible.value = false
        }
    }

    if (!show && !internalVisible.value) return

    // The top inset is read outside the dialog window: some platforms report no insets from within.
    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val captionBarPadding = WindowInsets.captionBar.asPaddingValues().calculateTopPadding()
    val displayCutoutPadding = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val safeTopInset = remember(statusBarsPadding, captionBarPadding, displayCutoutPadding) {
        maxOf(statusBarsPadding, captionBarPadding, displayCutoutPadding)
    }

    val windowInfo = LocalWindowInfo.current
    val sheetHeightPx = remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = { currentOnDismissRequest() },
        properties = platformDialogProperties(),
    ) {
        RemovePlatformDialogDefaultEffects()

        // Scrim between the app and the sheet; its alpha follows the slide so both end together.
        // It sits outside the IME padding, like the miuix dialogs, and taps away.
        val dimColor = MiuixTheme.colorScheme.windowDimming
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(dimColor.copy(alpha = dimColor.alpha * animationProgress.value))
                }
                .pointerInput(Unit) {
                    detectTapGestures { currentOnDismissRequest() }
                },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Consume taps on the sheet's own background so they never fall through to the
                    // scrim underneath, which would dismiss the sheet.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // Must precede the size and background modifiers so the transform wraps them all.
                    .graphicsLayer {
                        val height = if (sheetHeightPx.intValue > 0) {
                            sheetHeightPx.intValue.toFloat()
                        } else {
                            windowInfo.containerDpSize.height.toPx()
                        }
                        translationY = -height * (1f - animationProgress.value)
                    }
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .heightIn(max = windowInfo.containerDpSize.height * 0.85f)
                    .onGloballyPositioned { coordinates ->
                        sheetHeightPx.intValue = coordinates.size.height
                    }
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.background,
                        topStart = 0.dp,
                        topEnd = 0.dp,
                        bottomEnd = 28.dp,
                        bottomStart = 28.dp,
                    )
                    .padding(top = safeTopInset)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                content = content,
            )
        }
    }
}

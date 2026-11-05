package com.crko.ui.schedule

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FoldFlingVelocity = 400.dp

/**
 * Fold progress of the week/month calendar. It is owned above the grid so the drag surface can
 * span the title row as well as the calendar itself.
 */
@Stable
internal class CalendarFoldState internal constructor(
    private val progressState: MutableState<Float>,
    private val scope: CoroutineScope,
) {
    /** 0 reveals the selected week, 1 the whole month. */
    val progress: Float get() = progressState.value

    // Height hidden while collapsed; depends on how many weeks the selected month needs.
    private val foldPxState = mutableFloatStateOf(0f)
    internal val foldPx: Float get() = foldPxState.floatValue

    private var settleJob: Job? = null

    internal fun updateFoldPx(value: Float) {
        foldPxState.floatValue = value
    }

    internal fun dragBy(delta: Float) {
        if (foldPx <= 0f) return
        cancelSettle()
        progressState.value = (progress + delta / foldPx).coerceIn(0f, 1f)
    }

    internal fun cancelSettle() {
        settleJob?.cancel()
    }

    internal fun settle(target: Float) {
        cancelSettle()
        settleJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                animationSpec = tween(if (target > progress) 280 else 220),
            ) { value, _ -> progressState.value = value }
            progressState.value = target
        }
    }
}

@Composable
internal fun rememberCalendarFold(): CalendarFoldState {
    val scope = rememberCoroutineScope()
    // Saved with the screen so a configuration change keeps the settled fold position.
    val progress = rememberSaveable { mutableStateOf(0f) }
    return remember(progress, scope) { CalendarFoldState(progress, scope) }
}

/**
 * Vertical drag that folds and unfolds the calendar. Applied to the whole header block, so the
 * title row responds to the fold gesture as well as the grid.
 */
@Composable
internal fun Modifier.calendarFoldDrag(
    fold: CalendarFoldState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
): Modifier {
    val flingVelocityPx = with(LocalDensity.current) { FoldFlingVelocity.toPx() }
    return draggable(
        state = rememberDraggableState { delta -> fold.dragBy(delta) },
        orientation = Orientation.Vertical,
        onDragStarted = { fold.cancelSettle() },
        onDragStopped = { velocity ->
            val target = when {
                velocity >= flingVelocityPx -> 1f
                velocity <= -flingVelocityPx -> 0f
                fold.progress >= 0.5f -> 1f
                else -> 0f
            }
            if ((target == 1f) == expanded) fold.settle(target) else onExpandedChange(target == 1f)
        },
    )
}

/**
 * Height of the visible calendar window. The fold progress and the animated hidden height are read
 * while measuring, so dragging and settling only re-layout the header instead of recomposing it and
 * everything below.
 */
internal fun Modifier.foldRevealHeight(
    fold: CalendarFoldState,
    rowHeightPx: Float,
    animatedFoldPx: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        val height = (rowHeightPx + fold.progress * animatedFoldPx()).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/**
 * Slides the wrapped content down by [drop] as the calendar unfolds, and reserves the room it
 * slides into, so the content below is pushed down rather than overlapped. Like
 * [foldRevealHeight] the progress is read while laying out: a drag re-places the header without
 * recomposing it.
 */
internal fun Modifier.foldDrop(
    fold: CalendarFoldState,
    drop: Dp,
): Modifier =
    layout { measurable, constraints ->
        val dropPx = (fold.progress * drop.toPx()).roundToInt()
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height + dropPx) {
            placeable.place(0, dropPx)
        }
    }

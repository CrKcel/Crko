package com.crko.ui.schedule

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// Short nudges must not flip the screen, so a switch needs either this much travel or this much speed.
private val ViewSwitchDistance = 56.dp
private val ViewSwitchVelocity = 600.dp

/**
 * Swipe along [orientation] that switches between the timeline and the list.
 *
 * The gesture is decided on the slop frame: if [accepts] turns the pull down, the rest of the
 * gesture is consumed here and a long or fast enough swipe reports its signed travel, so the view
 * switch can animate in from the side the finger came from; otherwise nothing is consumed and the
 * scroller or pager underneath keeps the gesture untouched. Only one pointer is followed — a second
 * finger means the week overview pinch, not a view switch.
 */
@Composable
private fun Modifier.viewSwitchSwipe(
    orientation: Orientation,
    enabled: Boolean,
    accepts: (along: Float) -> Boolean,
    onSwipe: (travel: Float) -> Unit,
): Modifier {
    val currentAccepts by rememberUpdatedState(accepts)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val density = LocalDensity.current
    val distancePx = with(density) { ViewSwitchDistance.toPx() }
    val velocityPx = with(density) { ViewSwitchVelocity.toPx() }
    return pointerInput(orientation, enabled, distancePx, velocityPx) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var totalX = 0f
            var totalY = 0f
            // Undecided until the finger leaves the slop; then whether this swipe belongs to us.
            var claimed: Boolean? = null
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    cancelled = true
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // Another handler (the two-finger week overview) took the gesture over.
                if (claimed != true && change.isConsumed) {
                    cancelled = true
                    break
                }
                totalX += change.position.x - change.previousPosition.x
                totalY += change.position.y - change.previousPosition.y
                tracker.addPosition(change.uptimeMillis, change.position)
                if (claimed == null) {
                    val along = if (orientation == Orientation.Horizontal) totalX else totalY
                    val across = if (orientation == Orientation.Horizontal) totalY else totalX
                    when {
                        abs(along) >= viewConfiguration.touchSlop && abs(along) > abs(across) -> {
                            claimed = currentAccepts(along)
                            if (claimed == false) break
                        }
                        abs(across) >= viewConfiguration.touchSlop && abs(across) > abs(along) -> break
                    }
                }
                if (claimed == true) change.consume()
            }
            if (claimed == true && !cancelled) {
                val velocity = tracker.calculateVelocity()
                val travel = if (orientation == Orientation.Horizontal) totalX else totalY
                val speed = if (orientation == Orientation.Horizontal) velocity.x else velocity.y
                when {
                    abs(travel) >= distancePx -> currentOnSwipe(travel)
                    abs(speed) >= velocityPx -> currentOnSwipe(speed)
                }
            }
        }
    }
}

/**
 * Sideways swipe over the timeline. The timeline only scrolls vertically, so a horizontal drag is
 * free to switch to the list in either direction; [onSwipe] carries the signed travel.
 */
@Composable
internal fun Modifier.timelineSwipeToList(
    enabled: Boolean,
    onSwipe: (travel: Float) -> Unit,
): Modifier = viewSwitchSwipe(
    orientation = Orientation.Horizontal,
    enabled = enabled,
    accepts = { true },
    onSwipe = onSwipe,
)

/**
 * Vertical swipe over an agenda list that leaves for the timeline once the pull runs past an edge
 * the list cannot scroll over: down at the top, up at the bottom, either way when the day or week
 * fits the screen. Pulls the list can still scroll stay with the list; [onSwipe] carries the signed
 * travel.
 */
@Composable
internal fun Modifier.listSwipeToTimeline(
    listState: LazyListState,
    enabled: Boolean,
    onSwipe: (travel: Float) -> Unit,
): Modifier = viewSwitchSwipe(
    orientation = Orientation.Vertical,
    enabled = enabled,
    accepts = { along -> if (along > 0) !listState.canScrollBackward else !listState.canScrollForward },
    onSwipe = onSwipe,
)

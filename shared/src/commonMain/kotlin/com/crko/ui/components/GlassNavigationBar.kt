package com.crko.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.ui.theme.LocalIsDark
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** The selected bubble stays a subtle glass lens at rest (0..1 scale of the pressed bubble). */
private const val RestBubbleProgress = 0.25f

@Immutable
data class GlassNavigationBarItem(
    val icon: ImageVector,
    val label: String,
)

object GlassNavigationBarDefaults {
    val Height: Dp = 64.dp
    val HorizontalMargin: Dp = 20.dp

    private val ContentPadding: Dp = 4.dp
    private val PillHeight: Dp = 56.dp

    /** Space below the bar: the gesture area plus a breathing gap. */
    @Composable
    fun bottomMargin(): Dp {
        val systemInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        return maxOf(systemInset, 16.dp) + 12.dp
    }

    /** Height the bar occupies above the window bottom, for FAB/snackbar clearance. */
    @Composable
    fun clearance(): Dp = Height + bottomMargin()
}

/**
 * Floating navigation bar rendered as a Liquid Glass capsule over the content behind it.
 *
 * The selected tab is covered by a second glass bubble, as in the library's bottom tabs demo: the
 * bubble refracts the bar's own content (magnified and tinted) together with the page behind it,
 * and grows with a highlight, shadow and chromatic edge while pressed or dragged.
 *
 * [backdrop] must record the content the bar floats above, via `Modifier.layerBackdrop`, or the
 * bar draws only its own translucent surface.
 */
@Composable
fun GlassNavigationBar(
    backdrop: Backdrop,
    items: List<GlassNavigationBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val isDark = LocalIsDark.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val barShape = remember { AbsoluteRoundedCornerShape(percent = 50) }
    val pillShape = remember { AbsoluteRoundedCornerShape(percent = 50) }

    val containerColor = if (isDark) {
        Color(0xFF121212).copy(alpha = 0.4f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.4f)
    }
    val pillBaseColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)
    val contentColor = MiuixTheme.colorScheme.onSurface
    val accentColor = MiuixTheme.colorScheme.primary

    // Records the bar's own content so the bubble can refract (and magnify) the selected tab's
    // icon and label, combined with the page content behind the bar.
    val barContentBackdrop = rememberLayerBackdrop()
    val pillBackdrop = rememberCombinedBackdrop(backdrop, barContentBackdrop)

    val animationScope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    val pressValue = press.value
    val releaseSpec = spring<Float>(dampingRatio = 1f, stiffness = 700f)
    val pressSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f)

    var dragging by remember { mutableStateOf(false) }
    var dragJustEnded by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var touchPosition by remember { mutableStateOf(Offset.Zero) }

    val indicator by animateFloatAsState(
        targetValue = if (dragging) dragValue else selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
        label = "glass-tab-indicator",
    )
    val currentIndicator by rememberUpdatedState(indicator)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val tabCount = items.size

    // A tap (or an external selection change) briefly puffs the bubble, as the demo does when the
    // indicator travels between tabs. A drag already puffs it, so it just deflates on release.
    LaunchedEffect(selectedIndex) {
        if (dragJustEnded) {
            dragJustEnded = false
        } else if (!dragging) {
            press.animateTo(1f, pressSpec)
            press.animateTo(0f, spring(dampingRatio = 1f, stiffness = 350f))
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GlassNavigationBarDefaults.HorizontalMargin)
            .padding(bottom = GlassNavigationBarDefaults.bottomMargin()),
        contentAlignment = Alignment.Center,
    ) {
        val barWidth = minOf(maxWidth, 440.dp)
        val innerWidth = barWidth - 8.dp
        val tabWidth = innerWidth / tabCount
        val pillOffset = if (isRtl) {
            barWidth - 4.dp - tabWidth * (indicator + 1f)
        } else {
            4.dp + tabWidth * indicator
        }
        // The pill is already a (smaller) glass lens at rest, like the demo's selected bubble;
        // pressing grows it to the full 78/56 bubble.
        val bubbleProgress = RestBubbleProgress + (1f - RestBubbleProgress) * pressValue
        val bubbleScale = 1f + (78f / 56f - 1f) * bubbleProgress

        Box(
            modifier = Modifier
                .width(barWidth)
                .height(GlassNavigationBarDefaults.Height)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow.Default },
                    layerBlock = {
                        val scale = 1f + 0.04f * pressValue
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                // Specular highlight that follows the finger, drawn under the tab content.
                .drawWithContent {
                    if (pressValue > 0f) {
                        drawRect(
                            color = Color.White.copy(alpha = 0.08f * pressValue),
                            blendMode = BlendMode.Plus,
                        )
                        val radius = size.minDimension * 1.5f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.15f * pressValue),
                                    Color.Transparent,
                                ),
                                center = touchPosition,
                                radius = radius,
                            ),
                            radius = radius,
                            center = touchPosition,
                            blendMode = BlendMode.Plus,
                        )
                    }
                    drawContent()
                }
                // The glass is opaque to the eye but not to touches; without this, taps beside
                // the items would fall through to the timeline underneath.
                .pointerInput(Unit) { detectTapGestures { } }
                .pointerInput(tabCount) {
                    detectHorizontalDragGestures(
                        onDragStart = { position ->
                            touchPosition = position
                            dragValue = currentIndicator
                            dragging = true
                            animationScope.launch { press.animateTo(1f, pressSpec) }
                        },
                        onDragEnd = {
                            val target = dragValue.roundToInt().coerceIn(0, tabCount - 1)
                            dragging = false
                            if (target != currentSelectedIndex) dragJustEnded = true
                            currentOnSelect(target)
                            animationScope.launch { press.animateTo(0f, releaseSpec) }
                        },
                        onDragCancel = {
                            val target = dragValue.roundToInt().coerceIn(0, tabCount - 1)
                            dragging = false
                            if (target != currentSelectedIndex) dragJustEnded = true
                            currentOnSelect(target)
                            animationScope.launch { press.animateTo(0f, releaseSpec) }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        touchPosition = change.position
                        val widthPx = size.width / tabCount.toFloat()
                        if (widthPx > 0f) {
                            dragValue = (dragValue + dragAmount / widthPx)
                                .coerceIn(0f, (tabCount - 1).toFloat())
                        }
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val contentAlpha by animateFloatAsState(
                        targetValue = if (abs(indicator - index) < 0.15f) 0f else 1f,
                        animationSpec = tween(80),
                        label = "glass-tab-content-alpha",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .alpha(contentAlpha)
                            .selectable(
                                selected = index == selectedIndex,
                                onClick = { onSelect(index) },
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassTabContent(item = item, color = contentColor)
                    }
                }
            }

            // Invisible copy of the tabs, tinted with the accent and scaled while pressed, sampled
            // by the bubble so its refraction shows enlarged, accent-coloured content.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .alpha(0f)
                    .layerBackdrop(barContentBackdrop)
                    .graphicsLayer {
                        colorFilter = ColorFilter.tint(accentColor)
                    }
                    .clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    GlassTabContent(
                        item = item,
                        color = contentColor,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            // The selection bubble, drawn over the tabs like the demo.
            Box(
                modifier = Modifier
                    .offset(x = pillOffset, y = 4.dp)
                    .width(tabWidth)
                    .height(56.dp)
                    .drawBackdrop(
                        backdrop = pillBackdrop,
                        shape = { pillShape },
                        effects = {
                            lens(
                                refractionHeight = 10.dp.toPx() * bubbleProgress,
                                refractionAmount = 14.dp.toPx() * bubbleProgress,
                                chromaticAberration = true,
                            )
                        },
                        highlight = { Highlight.Default.copy(alpha = bubbleProgress) },
                        shadow = { Shadow(radius = 16.dp, alpha = 0.6f * bubbleProgress) },
                        innerShadow = {
                            InnerShadow(
                                radius = 8.dp * bubbleProgress,
                                alpha = bubbleProgress,
                            )
                        },
                        layerBlock = {
                            scaleX = bubbleScale
                            scaleY = bubbleScale
                        },
                        onDrawSurface = {
                            drawRect(pillBaseColor, alpha = 1f - bubbleProgress)
                            drawRect(Color.Black.copy(alpha = 0.03f * bubbleProgress))
                        },
                    ),
            )
        }
    }
}

@Composable
private fun GlassTabContent(
    item: GlassNavigationBarItem,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = item.label,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

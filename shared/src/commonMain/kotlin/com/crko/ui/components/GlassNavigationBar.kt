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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** How far the bubble grows when pressed, as a scale of its rest box. */
private const val RestBubblePressedScale = 78f / 56f

/** Inset of the tab slots inside the capsule. */
private val ContentPadding = 4.dp

private val PillHeight = 56.dp

/**
 * The one corner radius both outlines use. A percent shape would curve each box with half of its own
 * height, so the 64dp bar and the 56dp bubble read as two different curvatures.
 */
private val CornerRadius = PillHeight / 2

/**
 * The bubble's width as a fraction of its tab slot. Pressing scales it by [RestBubblePressedScale],
 * so the bubble stays clear of its neighbours at rest and spills a little over the slot when pressed.
 */
private const val PillWidthRatio = 0.80f

/**
 * Share of the slot's leftover air the bubble gives up toward the nearer end of the bar. 1f would
 * flush it with the capsule's inset, where its own cap crosses the bar's; 0.7f keeps a few dp between.
 */
private const val EdgeHug = 0.7f

/**
 * How far the bubble on [slot] shifts toward the nearer end of the bar, out of the air its slot
 * leaves around it. Signed, because the two ends pull opposite ways, and it fades to zero before
 * the middle slot so the bubble still travels smoothly.
 */
private fun edgeLean(slot: Float, tabCount: Int, slotInset: Dp): Dp {
    val toTrailingEnd = (1f - (tabCount - 1 - slot)).coerceIn(0f, 1f)
    val toLeadingEnd = (1f - slot).coerceIn(0f, 1f)
    return slotInset * EdgeHug * (toTrailingEnd - toLeadingEnd)
}

@Immutable
data class GlassNavigationBarItem(
    val icon: ImageVector,
    val label: String,
)

object GlassNavigationBarDefaults {
    val Height: Dp = 64.dp
    val HorizontalMargin: Dp = 36.dp

    /** Widest the capsule gets; past this the extra room stays empty on the sides. */
    val MaxWidth: Dp = 380.dp

    /** Space below the bar; see [barBottomGap] for why each platform picks its own distance. */
    @Composable
    fun bottomMargin(): Dp =
        barBottomGap(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

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
    val glassShape = remember { AbsoluteRoundedCornerShape(CornerRadius) }

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
        val barWidth = minOf(maxWidth, GlassNavigationBarDefaults.MaxWidth)
        val tabWidth = (barWidth - ContentPadding * 2) / tabCount
        val pillWidth = tabWidth * PillWidthRatio
        val slotInset = (tabWidth - pillWidth) / 2
        // On the outer tabs the bubble draws part of that leftover air back in, so it leans against
        // the capsule's end instead of floating in the middle of its slot. The tab's own label leans
        // by the same amount, which keeps each label inside its bubble.
        // `Modifier.offset` mirrors its x for RTL, so one leading-edge formula covers both directions.
        val pillOffset =
            ContentPadding + tabWidth * indicator + slotInset + edgeLean(indicator, tabCount, slotInset)
        // The pill is already a (smaller) glass lens at rest, like the demo's selected bubble;
        // pressing grows it to the full 78/56 bubble.
        val bubbleProgress = RestBubbleProgress + (1f - RestBubbleProgress) * pressValue
        val bubbleScale = 1f + (RestBubblePressedScale - 1f) * bubbleProgress

        Box(
            modifier = Modifier
                .width(barWidth)
                .height(GlassNavigationBarDefaults.Height)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { glassShape },
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
                    .padding(ContentPadding)
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
                        GlassTabContent(
                            item = item,
                            color = contentColor,
                            modifier = Modifier.offset(x = edgeLean(index.toFloat(), tabCount, slotInset)),
                        )
                    }
                }
            }

            // Invisible copy of the tabs, tinted with the accent and scaled while pressed, sampled
            // by the bubble so its refraction shows enlarged, accent-coloured content.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(ContentPadding)
                    .alpha(0f)
                    .layerBackdrop(barContentBackdrop)
                    .graphicsLayer {
                        colorFilter = ColorFilter.tint(accentColor)
                    }
                    .clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    GlassTabContent(
                        item = item,
                        color = contentColor,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .offset(x = edgeLean(index.toFloat(), tabCount, slotInset)),
                    )
                }
            }

            // The selection bubble, drawn over the tabs like the demo.
            Box(
                modifier = Modifier
                    .offset(x = pillOffset, y = (GlassNavigationBarDefaults.Height - PillHeight) / 2)
                    .width(pillWidth)
                    .height(PillHeight)
                    .drawBackdrop(
                        backdrop = pillBackdrop,
                        shape = { glassShape },
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

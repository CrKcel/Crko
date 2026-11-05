package com.crko.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import top.yukonga.miuix.kmp.utils.platformDialogProperties
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleSurface
import com.crko.ui.icons.AppIcons

/** Grouped card with miuix surface density and hairline separators between rows. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 16.dp),
    ) {
        content()
    }
}

@Composable
fun CardRowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f)),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
    )
}

@Composable
fun AddFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MiuixTheme.colorScheme.primary,
    ) {
        Icon(
            imageVector = AppIcons.Add,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onPrimary,
        )
    }
}


private const val DetailCardEnterMillis = 240
private const val DetailCardExitMillis = 170

/**
 * Centers its content as a floating miuix [Card] instead of the drawer-like presentation of a
 * window dialog. Tapping the dimmed area dismisses it; taps inside the card are consumed.
 *
 * [content] receives a close helper so buttons can play the exit animation before handing control
 * back: call it with the follow-up action (or `null` to simply dismiss) instead of invoking the
 * parent callback directly. The dialog stays mounted until the exit animation has finished.
 */
@Composable
fun DetailCardDialog(
    title: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(requestClose: (afterClose: (() -> Unit)?) -> Unit) -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DetailCardEnterMillis, easing = FastOutSlowInEasing))
    }

    fun requestClose(afterClose: (() -> Unit)?) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, tween(DetailCardExitMillis))
            if (afterClose != null) afterClose() else onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { requestClose(null) },
        properties = platformDialogProperties(),
    ) {
        RemovePlatformDialogDefaultEffects()
        val dimColor = MiuixTheme.colorScheme.windowDimming
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(dimColor.copy(alpha = dimColor.alpha * progress.value))
                }
                .pointerInput(Unit) { detectTapGestures { requestClose(null) } },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .widthIn(max = 480.dp)
                    .heightIn(max = windowInfo.containerDpSize.height * 0.86f)
                    .graphicsLayer {
                        val value = progress.value
                        alpha = value
                        scaleX = 0.94f + 0.06f * value
                        scaleY = 0.94f + 0.06f * value
                        translationY = 16.dp.toPx() * (1f - value)
                    }
                    .pointerInput(Unit) { detectTapGestures { } },
                cornerRadius = 24.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.background),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    title?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    content(this, ::requestClose)
                }
            }
        }
    }
}

/** Label/value line of a read-only detail card; the value wraps and stays end-aligned. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.62f),
        )
    }
}

/** Small tinted capsule used for status and priority badges on detail cards. */
@Composable
fun DetailPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 11.sp,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun PopupTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun PopupListLayout(content: @Composable () -> Unit) {
    ListPopupColumn { content() }
}

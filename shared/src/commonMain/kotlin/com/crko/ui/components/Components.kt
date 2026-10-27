package com.crko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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

/** Primary confirm-style button used in forms. */





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

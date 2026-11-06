package com.crko.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Todo
import com.crko.i18n.Strings
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One line in, one undated todo in the pool; the fast path never opens an editor. */
@Composable
internal fun QuickCapture(
    strings: Strings,
    focusRequester: FocusRequester,
    captureRequest: Int,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        onAdd(value)
        text = ""
    }

    SurfaceCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = strings.quickCaptureHint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(onClick = { submit() }, backgroundColor = androidx.compose.ui.graphics.Color.Transparent) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = strings.quickCaptureAdd,
                    tint = if (text.isBlank()) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    // The capture deep link asks for this field once; the counter lets a repeated link focus it again.
    androidx.compose.runtime.LaunchedEffect(captureRequest) {
        if (captureRequest > 0) focusRequester.requestFocus()
    }
}

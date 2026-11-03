package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Attachment
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.attachmentMimeType
import com.crko.domain.safeAttachmentFileName
import com.crko.i18n.Strings
import com.crko.ui.icons.AppIcons
import com.crko.ui.transfer.CalendarFileBridge
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MoreOptionsToggle(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AppIcons.Expand,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

@Composable
internal fun AttachmentRow(
    attachment: Attachment,
    strings: Strings,
    bridge: CalendarFileBridge,
    readAttachment: (Attachment) -> ByteArray?,
    onNotice: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val openable = attachment.uri.startsWith("http://") || attachment.uri.startsWith("https://")
    // Read after composition, once per attachment: a several-megabyte payload must not stall the
    // frame on the main thread, whether it is decoded or fetched from the attachment store.
    var payload by remember(attachment) { mutableStateOf<ByteArray?>(null) }
    var settled by remember(attachment) { mutableStateOf(!attachment.binary) }
    LaunchedEffect(attachment) {
        payload = if (attachment.binary) readAttachment(attachment) else null
        settled = true
    }
    val label = attachment.name.ifBlank {
        if (attachment.binary) strings.attachments else attachment.uri.ifBlank { strings.attachments }
    }
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2,
        fontSize = 13.sp,
        color = if (openable || payload != null) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = openable || attachment.binary) {
                if (attachment.binary) {
                    val bytes = payload
                    when {
                        bytes != null -> bridge.saveAttachment(
                            fileName = safeAttachmentFileName(attachment),
                            bytes = bytes,
                            mimeType = attachmentMimeType(attachment),
                            onSaved = {},
                            onError = { onNotice(strings.attachmentSaveFailed) },
                        )
                        // Only a finished read can call the payload broken; an early click on a
                        // large file is still in flight and says nothing about the payload.
                        settled -> onNotice(strings.attachmentBroken)
                    }
                } else if (openable) {
                    uriHandler.openUri(attachment.uri)
                }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** Location, categories, URL, attachments, status, transparency and priority of the draft. */
@Composable
internal fun MoreOptionsSection(
    state: EventEditorState,
    strings: Strings,
    attachments: List<Attachment>,
    readAttachment: (Attachment) -> ByteArray?,
) {
    TextField(
        value = state.location,
        onValueChange = { state.location = it },
        label = strings.locationField,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    TextField(
        value = state.categories,
        onValueChange = { state.categories = it },
        label = strings.categoriesField,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    TextField(
        value = state.url,
        onValueChange = { state.url = it },
        label = strings.urlField,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (attachments.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Caption(strings.attachments)
        attachments.forEach { attachment ->
            AttachmentRow(attachment, strings, state.fileBridge, readAttachment) { state.attachmentNotice = it }
        }
        state.attachmentNotice?.let { notice ->
            Text(
                text = notice,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Caption(strings.eventStatus)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.statusConfirmed, state.status == EventStatus.Confirmed, {
                    state.status = EventStatus.Confirmed
                }, Modifier.weight(1f))
                RepeatChip(strings.statusTentative, state.status == EventStatus.Tentative, {
                    state.status = EventStatus.Tentative
                }, Modifier.weight(1f))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Caption(strings.transparency)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.busy, state.transparency == EventTransparency.Opaque, {
                    state.transparency = EventTransparency.Opaque
                }, Modifier.weight(1f))
                RepeatChip(strings.free, state.transparency == EventTransparency.Transparent, {
                    state.transparency = EventTransparency.Transparent
                }, Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Caption(strings.priority)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RepeatChip(strings.priorityNone, state.priority == null, { state.priority = null }, Modifier.weight(1f))
        RepeatChip(strings.priorityHigh, state.priority == 1, { state.priority = 1 }, Modifier.weight(1f))
        RepeatChip(strings.priorityNormal, state.priority == 5, { state.priority = 5 }, Modifier.weight(1f))
        RepeatChip(strings.priorityLow, state.priority == 9, { state.priority = 9 }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
}

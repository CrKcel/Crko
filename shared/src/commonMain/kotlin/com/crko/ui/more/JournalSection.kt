package com.crko.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Journal
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import com.crko.ui.schedule.DatePickerDialog
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Browsing and editing of the `VJOURNAL` records: one card listing every journal newest first, a
 * row to start a new one, and an editor dialog for the content of a single entry.
 */
@Composable
internal fun JournalSection(state: AppStateHolder, strings: Strings) {
    val journals = state.journalsSorted()
    var editing by remember { mutableStateOf<Journal?>(null) }
    var creating by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                .padding(6.dp),
        ) {
            Icon(
                AppIcons.ListView,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text = strings.journals, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            text = journals.size.toString(),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    Spacer(Modifier.height(12.dp))
    SurfaceCard {
        JournalRow(
            title = strings.journalNew,
            subtitle = null,
            onClick = { creating = true },
            accent = MiuixTheme.colorScheme.primary,
        )
        journals.forEach { journal ->
            CardRowDivider()
            JournalRow(
                title = journal.title.ifBlank { journalDateLabel(journal, strings) },
                subtitle = journalPreview(journal, strings),
                onClick = { editing = journal },
                accent = MiuixTheme.colorScheme.onSurface,
            )
        }
        if (journals.isEmpty()) {
            CardRowDivider()
            Text(
                text = strings.journalEmpty,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }

    JournalEditorDialog(
        show = creating,
        strings = strings,
        today = state.today,
        journal = null,
        onDismiss = { creating = false },
        onSave = { title, note, date ->
            state.addJournal(title, note, date) == WriteResult.Success
        },
    )
    editing?.let { journal ->
        JournalEditorDialog(
            show = true,
            strings = strings,
            today = state.today,
            journal = journal,
            onDismiss = { editing = null },
            onSave = { title, note, date ->
                state.updateJournal(journalWithEdits(journal, title, note, date)) == WriteResult.Success
            },
            onDelete = {
                state.deleteJournal(journal.id)
                editing = null
            },
        )
    }
}

/** Parses a stored journal date; malformed values fall back to the untitled placeholder. */
private fun parseJournalDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text) }.getOrNull()

/**
 * The journal with the editor's changes applied. The editor picks a date only, so an imported
 * `DTSTART` instant tied to the old date is dropped rather than exported over the picked date.
 */
internal fun journalWithEdits(journal: Journal, title: String, note: String, date: LocalDate?): Journal {
    val startTime = journal.startTime?.takeIf { date != null && date.toString() == journal.date }
    return journal.copy(title = title.trim(), note = note, date = date?.toString(), startTime = startTime)
}

/** The journal's own date, or the placeholder the import preview uses for undated records. */
private fun journalDateLabel(journal: Journal, strings: Strings): String =
    journal.date?.let(::parseJournalDate)?.let(strings::dayMonth) ?: strings.untitledEvent

/** One-line preview: the first bit of the note, or the date when the note is empty. */
private fun journalPreview(journal: Journal, strings: Strings): String? =
    journal.note.lineSequence().firstOrNull { it.isNotBlank() }
        ?: journal.date?.let(::parseJournalDate)?.let(strings::dayMonth)

@Composable
private fun JournalRow(title: String, subtitle: String?, onClick: () -> Unit, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.15f))
                .padding(6.dp),
        ) {
            Icon(AppIcons.ListView, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun JournalEditorDialog(
    show: Boolean,
    strings: Strings,
    today: LocalDate,
    journal: Journal?,
    onDismiss: () -> Unit,
    onSave: (String, String, LocalDate?) -> Boolean,
    onDelete: () -> Unit = {},
) {
    if (!show) return
    // Keyed on the entry and [show]: editing a different journal starts from its content, and a
    // reopened dialog starts blank instead of keeping the last session's input.
    var title by remember(journal, show) { mutableStateOf(journal?.title ?: "") }
    var note by remember(journal, show) { mutableStateOf(journal?.note ?: "") }
    var date by remember(journal, show) { mutableStateOf(journal?.date?.let(::parseJournalDate)) }
    var showDatePicker by remember(journal, show) { mutableStateOf(false) }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = if (journal == null) strings.journalNew else strings.journalEdit,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TextField(
                value = title,
                onValueChange = { title = it },
                label = strings.eventTitleField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = strings.journalDate,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateChip(
                    text = strings.today,
                    selected = date == today,
                    onClick = { date = if (date == today) null else today },
                    modifier = Modifier.weight(1f),
                )
                val custom = date?.takeIf { it != today }
                DateChip(
                    text = custom?.let(strings::dayMonth) ?: strings.customDate,
                    selected = custom != null,
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                )
                DateChip(
                    text = strings.clear,
                    selected = date == null,
                    onClick = { date = null },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (journal != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.delete,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDelete)
                        .padding(vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (onSave(title, note, date)) onDismiss() },
                enabled = title.isNotBlank() || note.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            initial = date ?: today,
            today = today,
            strings = strings,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun DateChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

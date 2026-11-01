package com.crko.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.crko.domain.CalendarSubscription
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Management of subscribed calendars: every subscription syncs on tap, and a dialog adds a new one
 * from a pasted webcal/CalDAV link. Sync bookkeeping lives in the state holder, so the rows stay a
 * plain view of [com.crko.domain.AppState.subscriptions].
 */
@Composable
fun SubscriptionsSection(state: AppStateHolder, strings: Strings) {
    val subscriptions = state.appState.subscriptions
    var showAddDialog by remember { mutableStateOf(false) }
    if (showAddDialog) {
        AddSubscriptionDialog(
            strings = strings,
            onConfirm = { name, url ->
                val added = state.addSubscription(name, url)
                if (added != null) {
                    state.syncSubscription(added.id)
                    showAddDialog = false
                }
            },
            onDismiss = { showAddDialog = false },
        )
    }
    SurfaceCard {
        if (subscriptions.isEmpty()) {
            Text(
                text = strings.subscriptionsEmpty,
                style = MiuixTheme.textStyles.footnote1,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        subscriptions.forEachIndexed { index, subscription ->
            SubscriptionRow(
                subscription = subscription,
                strings = strings,
                syncing = state.isSyncing(subscription.id),
                onSync = { state.syncSubscription(subscription.id) },
                onDelete = { state.removeSubscription(subscription.id) },
            )
            if (index != subscriptions.lastIndex) CardRowDivider()
        }
        if (subscriptions.isNotEmpty()) CardRowDivider()
        CardRow(
            icon = AppIcons.Add,
            title = strings.subscriptionAdd,
            subtitle = strings.subscriptionAddHint,
            onClick = { showAddDialog = true },
        )
    }
}

@Composable
private fun SubscriptionRow(
    subscription: CalendarSubscription,
    strings: Strings,
    syncing: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(
        onClick = onSync,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (syncing) "${subscription.name}…" else subscription.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subscriptionSubtitle(subscription, strings),
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = AppIcons.Delete,
                contentDescription = strings.delete,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDelete)
                    .padding(2.dp),
            )
        }
    }
}

private fun subscriptionSubtitle(subscription: CalendarSubscription, strings: Strings): String =
    subscription.lastSync?.let { last ->
        strings.subscriptionLastSynced(formatSyncTime(last, strings))
    } ?: strings.subscriptionNeverSynced

/** Local date and time of a stored sync instant, in the wording the rest of the app uses. */
private fun formatSyncTime(instantText: String, strings: Strings): String =
    runCatching { kotlin.time.Instant.parse(instantText) }.getOrNull()
        ?.toLocalDateTime(TimeZone.currentSystemDefault())
        ?.let { "${strings.dayMonth(it.date)} ${strings.time(it.time)}" }
        ?: strings.subscriptionNeverSynced

@Composable
private fun CardRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    BasicComponent(onClick = onClick, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(6.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Dialog that adds a subscription from a pasted URL; the name may stay empty. */
@Composable
private fun AddSubscriptionDialog(
    strings: Strings,
    onConfirm: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showInvalid by remember { mutableStateOf(false) }
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.subscriptionAdd,
        summary = strings.subscriptionsHint,
    ) {
        Column {
            TextField(
                value = url,
                onValueChange = {
                    url = it
                    showInvalid = false
                },
                label = strings.subscriptionUrl,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showInvalid) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = strings.subscriptionUrlInvalid,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                label = strings.subscriptionName,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (url.isBlank()) {
                        showInvalid = true
                    } else {
                        onConfirm(name, url)
                    }
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }
}
